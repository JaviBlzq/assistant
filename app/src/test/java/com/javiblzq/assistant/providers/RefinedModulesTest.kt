package com.javiblzq.assistant.providers

import android.content.ContextWrapper
import com.javiblzq.assistant.MainActivity
import com.javiblzq.assistant.data.*
import com.javiblzq.assistant.ui.DashboardRenderer
import com.javiblzq.assistant.ui.RouteMiniMapView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.Calendar
import java.util.TimeZone

class RefinedModulesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun todayTrainingUsesNameOnlyForStrengthAndShortRunDescription() {
        val gym = HarbizWorkout("g", "Push 1", "2026-11-24", 0L, 0L, false, null, emptyList(), 0, 0.0)
        val run = StravaActivitySummary(1L, "Rodaje", "Run", "2026-11-24T08:00:00", "2026-11-24", 8000.0, 2400, 2400, 0.0, 3.3, 140.0, null, null)
        val items = SyncCoordinator.buildTodayTrainings("2026-11-24", listOf(gym), gym, listOf(run), null, AssistantConfig())
        assertEquals("PUSH 1", items[0].title)
        assertEquals("", items[0].subtitle)
        assertEquals("40' Z2", items[1].subtitle)
    }

    @Test fun decodesPrecisionFivePolyline() {
        val points = CommuteProvider.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        val expected = listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453)
        assertEquals(3, points.size)
        expected.zip(points).forEach { (a, b) ->
            assertEquals(a.first, b.first, 0.00001)
            assertEquals(a.second, b.second, 0.00001)
        }
    }

    @Test fun telegramCommandsUpdateNotesAndTasks() {
        val store = LocalStore(object : ContextWrapper(null) {
            override fun getFilesDir() = folder.root
        })
        TelegramBotService.applyIncomingText(store, "nota: Llevar agua")
        assertEquals("Llevar agua", store.config.pinnedNote)
        TelegramBotService.applyIncomingText(store, "/nota Comprar pan")
        assertEquals("Comprar pan", store.config.pinnedNote)
        TelegramBotService.applyIncomingText(store, "Normal")
        TelegramBotService.applyIncomingText(store, "! Urgente")
        assertEquals("Urgente", store.config.tasks[0].text)
        assertTrue(store.config.tasks[0].priority)
        assertFalse(store.config.tasks[1].priority)
        TelegramBotService.applyIncomingText(store, "/done 1")
        assertTrue(store.config.tasks[0].done)
        TelegramBotService.applyIncomingText(store, "/clear")
        assertEquals(listOf("Normal"), store.config.tasks.map { it.text })
    }

    @Test fun routeMiniMapWebMercatorProjectionAndZoomSelection() {
        val wx0 = RouteMiniMapView.lonToWorldX(-3.7038, 14)
        val wy0 = RouteMiniMapView.latToWorldY(40.4168, 14)
        assertEquals(8023, (wx0 / 256.0).toInt())
        assertEquals(6177, (wy0 / 256.0).toInt())

        val (zoom, scale) = RouteMiniMapView.chooseTileZoom(
            minLat = 40.4168,
            maxLat = 40.4400,
            minLon = -3.7038,
            maxLon = -3.6900,
            viewW = 420f,
            viewH = 260f,
            baseScale = 1f
        )
        assertTrue(zoom in 10..16)
        assertTrue(scale in 0.72f..1.85f)
    }

    @Test fun trafficConfigIntervalProviderOrderAndTomTomParsing() {
        val cfg = AssistantConfig(trafficIntervalMinutes = 3, trafficProvider = "tomtom", tomtomApiKey = "demo-key")
        val roundTrip = AssistantConfig.fromJson(cfg.toJson())
        assertEquals(3, roundTrip.trafficIntervalMinutes)
        assertEquals(10, AssistantConfig().trafficIntervalMinutes)
        assertEquals(10, AssistantConfig.fromJson(JSONObject()).trafficIntervalMinutes)
        assertEquals(1, AssistantConfig.fromJson(JSONObject().put("trafficIntervalMinutes", 0)).trafficIntervalMinutes)
        assertEquals(60, AssistantConfig.fromJson(JSONObject().put("trafficIntervalMinutes", 120)).trafficIntervalMinutes)

        val provider = CommuteProvider()
        assertEquals(listOf("tomtom", "osrm"), provider.resolveProviderOrder(cfg))
        assertEquals(
            listOf("tomtom", "osrm"),
            provider.resolveProviderOrder(AssistantConfig(trafficProvider = "osrm", tomtomApiKey = "demo-key"))
        )
        assertEquals(
            listOf("google", "tomtom", "osrm"),
            provider.resolveProviderOrder(
                AssistantConfig(trafficProvider = "google", googleMapsApiKey = "g-key", tomtomApiKey = "t-key")
            )
        )

        val tomtomBody = """
            {
              "routes": [{
                "summary": {
                  "lengthInMeters": 12460,
                  "travelTimeInSeconds": 1380,
                  "trafficDelayInSeconds": 240
                },
                "legs": [{
                  "points": [
                    {"latitude": 40.4168, "longitude": -3.7038},
                    {"latitude": 40.4280, "longitude": -3.6970},
                    {"latitude": 40.4400, "longitude": -3.6900}
                  ]
                }]
              }]
            }
        """.trimIndent()
        val route = provider.parseTomTomResponse(tomtomBody, 40.4168, -3.7038, 40.4400, -3.6900, "TRABAJO", "CASA")!!
        assertEquals(23, route.durationMin)
        assertEquals(12.5, route.distanceKm, 0.01)
        assertEquals(4, route.trafficDelayMin)
        assertEquals("TOMTOM LIVE", route.providerLabel)
        assertEquals("+4m TRAFICO", route.statusLabel)
        assertEquals(3, route.polylinePoints.size)
        assertTrue(route.polylinePoints.any { it.congestionLevel >= 1 })
    }

    @Test fun commuteFreshnessFormattingAndTargetedSync() {
        val now = 1_700_000_000_000L
        assertEquals("SIN ACTUALIZAR", DashboardRenderer.formatFreshness(0L, now))
        assertEquals("ACTUALIZADO AHORA", DashboardRenderer.formatFreshness(now - 35_000L, now))
        assertEquals("HACE 2 MIN", DashboardRenderer.formatFreshness(now - 125_000L, now))
        assertEquals("HACE 3 H", DashboardRenderer.formatFreshness(now - 3 * 3_600_000L, now))

        val store = LocalStore(object : ContextWrapper(null) {
            override fun getFilesDir() = folder.root
        })
        val fakeProvider = object : CommuteProvider() {
            override fun fetchRoute(config: AssistantConfig, targetName: String, destLat: Double, destLon: Double): CommuteRoute {
                return CommuteRoute(
                    targetName = targetName,
                    originLabel = config.homeLabel,
                    durationMin = if (targetName == config.gymLabel) 14 else 22,
                    distanceKm = 8.4,
                    trafficDelayMin = 3,
                    providerLabel = "TOMTOM LIVE",
                    statusLabel = "+3m TRAFICO",
                    fetchedAtMs = now
                )
            }
        }
        val coordinator = SyncCoordinator(store, fakeProvider)
        coordinator.runCommuteSyncBlocking(SyncCoordinator.CommuteSyncTarget.GYM)
        assertNull(store.snapshot.workCommute)
        assertEquals(14, store.snapshot.gymCommute?.durationMin)
        assertEquals("TOMTOM LIVE", store.snapshot.gymCommute?.providerLabel)
        assertFalse(store.snapshot.commuteSyncing)

        coordinator.runCommuteSyncBlocking(SyncCoordinator.CommuteSyncTarget.WORK)
        assertEquals(22, store.snapshot.workCommute?.durationMin)
        assertEquals(14, store.snapshot.gymCommute?.durationMin)
        assertFalse(store.snapshot.commuteSyncing)
    }

    @Test fun trafficSyncAlignsToTenMinuteClockMarks() {
        val tz = TimeZone.getTimeZone("Europe/Madrid")
        fun ts(year: Int, month: Int, day: Int, hour: Int, min: Int, sec: Int = 0, ms: Int = 0): Long {
            return Calendar.getInstance(tz).apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, min)
                set(Calendar.SECOND, sec)
                set(Calendar.MILLISECOND, ms)
            }.timeInMillis
        }

        // Exact mark :00 -> :10:00.000
        assertEquals(
            ts(2026, Calendar.NOVEMBER, 26, 12, 10, 0, 0),
            MainActivity.nextClockAlignedCommuteSyncMs(ts(2026, Calendar.NOVEMBER, 26, 12, 0, 0, 0), 10, tz)
        )
        // Mid-interval 12:03:45.678 -> 12:10:00.000
        assertEquals(
            ts(2026, Calendar.NOVEMBER, 26, 12, 10, 0, 0),
            MainActivity.nextClockAlignedCommuteSyncMs(ts(2026, Calendar.NOVEMBER, 26, 12, 3, 45, 678), 10, tz)
        )
        // Last millisecond before mark 12:09:59.999 -> 12:10:00.000
        assertEquals(
            ts(2026, Calendar.NOVEMBER, 26, 12, 10, 0, 0),
            MainActivity.nextClockAlignedCommuteSyncMs(ts(2026, Calendar.NOVEMBER, 26, 12, 9, 59, 999), 10, tz)
        )

        // Full cycle through :10, :20, :30, :40, :50, and next hour :00
        var cursor = ts(2026, Calendar.NOVEMBER, 26, 12, 0, 12, 0)
        val expectedMarks = listOf(
            12 to 10,
            12 to 20,
            12 to 30,
            12 to 40,
            12 to 50,
            13 to 0
        )
        for ((expectedHour, expectedMin) in expectedMarks) {
            cursor = MainActivity.nextClockAlignedCommuteSyncMs(cursor, 10, tz)
            val cal = Calendar.getInstance(tz).apply { timeInMillis = cursor }
            assertEquals(expectedHour, cal.get(Calendar.HOUR_OF_DAY))
            assertEquals(expectedMin, cal.get(Calendar.MINUTE))
            assertEquals(0, cal.get(Calendar.SECOND))
            assertEquals(0, cal.get(Calendar.MILLISECOND))
        }

        // Midnight rollover 23:55:12 -> next day 00:00:00.000
        val midnightNext = MainActivity.nextClockAlignedCommuteSyncMs(
            ts(2026, Calendar.NOVEMBER, 26, 23, 55, 12, 0), 10, tz
        )
        assertEquals(ts(2026, Calendar.NOVEMBER, 27, 0, 0, 0, 0), midnightNext)
    }

    @Test fun tomtomApiKeyValidationAndMasking() {
        assertFalse(CommuteProvider.isUsableApiKey(""))
        assertFalse(CommuteProvider.isUsableApiKey("   "))
        assertFalse(CommuteProvider.isUsableApiKey("••••••"))
        assertTrue(CommuteProvider.isRedactedPlaceholder("••••••"))
        assertTrue(CommuteProvider.isUsableApiKey("abc123XYZ789"))

        val provider = CommuteProvider()
        assertEquals(
            listOf("osrm"),
            provider.resolveProviderOrder(AssistantConfig(trafficProvider = "tomtom", tomtomApiKey = "••••••"))
        )

        val masked = CommuteProvider.maskKeyInUrl(
            "https://api.tomtom.com/routing/1/calculateRoute/40.0,-3.0:40.1,-3.1/json?traffic=true&key=mySecretKey999",
            "mySecretKey999"
        )
        assertFalse(masked.contains("mySecretKey999"))
        assertTrue(masked.contains("key=myS***999"))
    }

    @Test fun quickTaskRoundTripWithDueDateAndUrgency() {
        val t = QuickTask(
            id = "t1",
            text = "Pagar seguro",
            done = false,
            priority = false,
            dueDate = "28/11",
            urgency = QuickTask.URGENCY_MED
        )
        val parsed = QuickTask.fromJson(t.toJson())
        assertEquals("Pagar seguro", parsed.text)
        assertEquals("28/11", parsed.dueDate)
        assertEquals(QuickTask.URGENCY_MED, parsed.urgency)
        assertFalse(parsed.priority)

        val legacyPriority = QuickTask.fromJson(
            JSONObject().put("id", "t2").put("text", "Urgente").put("priority", true)
        )
        assertEquals(QuickTask.URGENCY_HIGH, legacyPriority.normalizedUrgency)
        assertTrue(legacyPriority.priority)
    }

    @Test fun telegramGuidedFlowHandlesRemindersAgendaIcsAndIgnoresChatNoise() {
        val store = LocalStore(object : ContextWrapper(null) {
            override fun getFilesDir() = folder.root
        })
        store.updateConfig { it.copy(telegramBotToken = "fake-token", telegramAllowedChatId = "100") }

        val sentMessages = mutableListOf<Pair<String, String>>()
        data class SentDoc(val chatId: String, val filename: String, val content: String, val caption: String)
        val sentDocs = mutableListOf<SentDoc>()

        val fakeTransport = object : TelegramTransport {
            override fun sendMessage(token: String, chatId: String, text: String): Boolean {
                sentMessages.add(chatId to text)
                return true
            }

            override fun sendDocument(
                token: String,
                chatId: String,
                filename: String,
                bytes: ByteArray,
                caption: String
            ): Boolean {
                sentDocs.add(SentDoc(chatId, filename, String(bytes, Charsets.UTF_8), caption))
                return true
            }
        }

        val bot = TelegramBotService(store = store, transport = fakeTransport)
        val fixedNow = Calendar.getInstance(TimeZone.getTimeZone("Europe/Madrid")).apply {
            set(2026, Calendar.NOVEMBER, 26, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // 1. /start and random chat must NOT create tablet tasks
        bot.handleIncomingMessage("fake-token", "100", "/start", fixedNow)
        bot.handleIncomingMessage("fake-token", "100", "Hola que tal", fixedNow)
        assertTrue(store.config.tasks.isEmpty())

        // 2. Guided /new -> 1 (recordatorio) -> text -> date -> alta
        bot.handleIncomingMessage("fake-token", "100", "/new", fixedNow)
        assertEquals(TelegramBotService.BotStep.AWAITING_NEW_CHOICE, bot.currentStep("100"))
        bot.handleIncomingMessage("fake-token", "100", "1", fixedNow)
        assertEquals(TelegramBotService.BotStep.AWAITING_RECORD_TEXT, bot.currentStep("100"))
        bot.handleIncomingMessage("fake-token", "100", "Llevar coche al taller", fixedNow)
        assertEquals(TelegramBotService.BotStep.AWAITING_RECORD_DATE, bot.currentStep("100"))
        bot.handleIncomingMessage("fake-token", "100", "mañana", fixedNow)
        assertEquals(TelegramBotService.BotStep.AWAITING_RECORD_URGENCY, bot.currentStep("100"))
        bot.handleIncomingMessage("fake-token", "100", "alta", fixedNow)
        assertEquals(TelegramBotService.BotStep.IDLE, bot.currentStep("100"))

        assertEquals(1, store.config.tasks.size)
        val savedTask = store.config.tasks[0]
        assertEquals("Llevar coche al taller", savedTask.text)
        assertEquals("27/11", savedTask.dueDate)
        assertEquals(QuickTask.URGENCY_HIGH, savedTask.normalizedUrgency)
        assertTrue(savedTask.priority)

        // 3. Direct /record one-liner
        bot.handleIncomingMessage("fake-token", "100", "/record Comprar agua | 28/11 | media", fixedNow)
        assertEquals(2, store.config.tasks.size)
        assertEquals("Comprar agua", store.config.tasks[0].text)
        assertEquals("28/11", store.config.tasks[0].dueDate)
        assertEquals(QuickTask.URGENCY_MED, store.config.tasks[0].normalizedUrgency)

        // 4. Direct /agenda one-liner -> saves ManualCalendarEvent, updates snapshot, sends .ics without VALARM
        bot.handleIncomingMessage("fake-token", "100", "/agenda MEDICO Dentista | 2026-11-28 | 17:30", fixedNow)
        assertEquals(1, store.config.manualEvents.size)
        val ev = store.config.manualEvents[0]
        assertEquals("[MEDICO] Dentista", ev.title)
        assertEquals("2026-11-28", ev.dateIso)
        assertEquals("17:30", ev.startTime)
        assertTrue(store.snapshot.calendarEvents.any { it.title == "[MEDICO] Dentista" })

        assertEquals(1, sentDocs.size)
        val doc = sentDocs[0]
        assertTrue(doc.filename.endsWith(".ics"))
        assertTrue(doc.content.contains("BEGIN:VCALENDAR"))
        assertTrue(doc.content.contains("SUMMARY:[MEDICO] Dentista"))
        assertTrue(doc.content.contains("DTSTART:20261128T173000"))
        assertTrue(doc.content.contains("DTEND:20261128T183000"))
        assertFalse("ICS must not contain VALARM by default", doc.content.contains("VALARM"))

        // 5. /cancel resets active flow
        bot.handleIncomingMessage("fake-token", "100", "/new", fixedNow)
        assertEquals(TelegramBotService.BotStep.AWAITING_NEW_CHOICE, bot.currentStep("100"))
        bot.handleIncomingMessage("fake-token", "100", "/cancel", fixedNow)
        assertEquals(TelegramBotService.BotStep.IDLE, bot.currentStep("100"))
    }

    @Test
    fun visibleTasksHidesCompletedWhileKeepingThemInStorage() {
        val allTasks = listOf(
            QuickTask(id = "1", text = "Pendiente A", done = false),
            QuickTask(id = "2", text = "Completada", done = true),
            QuickTask(id = "3", text = "Pendiente B", done = false)
        )

        val visible = com.javiblzq.assistant.ui.DashboardRenderer.visibleTasks(allTasks)

        assertEquals(listOf("1", "3"), visible.map { it.id })
        assertEquals(3, allTasks.size)
    }

    @Test
    fun satelliteTrackerParsesTlesExcludesGeoPropagatesForMadridAndMergesHardwareGnss() {
        val sampleTles = """
            GSAT0207 (GALILEO 11)
            1 41174U 15079A   26330.00000000  .00000010  00000-0  00000-0 0  9995
            2 41174  55.3000  15.0000 0003000  50.0000 250.0000  1.70475000 69005
            GPS BIIR-2  (PRN 13)
            1 24876U 97035A   26330.00000000 -.00000020  00000-0  00000-0 0  9991
            2 24876  55.1000  20.0000 0050000  45.0000  15.0000  2.0056300021501
            SENTINEL-2A
            1 40697U 15028A   26330.00000000  .00000120  00000-0  35000-4 0  9991
            2 40697  98.5680  42.0000 0001300  85.0000 110.0000 14.3081800059002
            ISS (ZARYA)
            1 25544U 98067A   26330.00000000  .00016717  00000-0  29800-3 0  9990
            2 25544  51.6400 120.0000 0005000  90.0000  45.0000 15.5010000054001
            GALILEO GEO FAKE
            1 99999U 24001A   26330.00000000  .00000000  00000-0  00000-0 0  9999
            2 99999   0.0500  10.0000 0001000   0.0000 100.0000  1.00270000 10001
            BEIDOU-3 M1
            1 43001U 17069A   26330.00000000  .00000000  00000-0  00000-0 0  9999
            2 43001  55.0000  20.0000 0010000  10.0000  10.0000  1.86230000 10001
        """.trimIndent()

        val parsed = SatelliteTrackerProvider.parseTleCatalog(sampleTles)
        assertEquals(4, parsed.size)
        assertEquals("2015-12-17", parsed.first { it.constellation == "GALILEO" }.launchDate)
        assertEquals("1997-07-23", parsed.first { it.constellation == "GPS" }.launchDate)
        assertEquals("2015-06-23", parsed.first { it.shortName == "SNT2A" }.launchDate)
        assertEquals("1998-11-20", parsed.first { it.shortName == "ISS" }.launchDate)
        assertEquals(98.568f, parsed.first { it.shortName == "SNT2A" }.inclinationDeg, 0.001f)
        assertEquals("ESA / EUSPA (UE)", parsed.first { it.constellation == "GALILEO" }.operator)
        assertEquals("USSF / SPACE FORCE (EE.UU.)", parsed.first { it.constellation == "GPS" }.operator)
        assertEquals("ESA / COPERNICUS (UE)", parsed.first { it.shortName == "SNT2A" }.operator)
        assertTrue(parsed.any { it.shortName == "E11" && it.agency == "EU" && it.constellation == "GALILEO" })
        assertTrue(parsed.any { it.shortName == "G13" && it.agency == "US" && it.constellation == "GPS" })
        assertTrue(parsed.any { it.shortName == "SNT2A" && it.agency == "EU" && it.constellation == "ESA" })
        assertTrue(parsed.any { it.shortName == "ISS" && it.agency == "US" && it.constellation == "ISS" })
        assertFalse("GEO satellites must be excluded", parsed.any { it.id == "99999" })
        assertFalse("Non-EU/US satellites must be excluded", parsed.any { it.id == "43001" })

        val cacheFile = java.io.File(folder.root, SatelliteTrackerProvider.CACHE_FILE_NAME)
        val tracker = SatelliteTrackerProvider(cacheFile = cacheFile, fetcher = { null })
        val fixedNow = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 26, 15, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val hwSats = listOf(
            HardwareGnssSatellite(prn = 13, azimuthDeg = 140f, elevationDeg = 48f, snr = 34f, usedInFix = true),
            HardwareGnssSatellite(prn = 31, azimuthDeg = 210f, elevationDeg = 32f, snr = 28f, usedInFix = false)
        )

        val sky = tracker.computeOverhead(
            observerLatDeg = 40.416775,
            observerLonDeg = -3.703790,
            nowMs = fixedNow,
            hardwareSatellites = hwSats,
            hardwareFixActive = true
        )

        assertTrue("Expected overhead EU satellites for Madrid", sky.euCount > 0)
        assertTrue("Expected overhead US satellites for Madrid", sky.usCount > 0)
        assertTrue(sky.hardwareFixActive)
        assertEquals(2, sky.hardwareSatCount)
        assertTrue(sky.satellites.all { it.elevationDeg > 5f && it.elevationDeg <= 90f })
        assertTrue(sky.satellites.all { it.azimuthDeg >= 0f && it.azimuthDeg < 360f })
        assertTrue("All plotted satellites must be non-GEO (<= 30000 km)", sky.satellites.all { it.altitudeKm <= 30_000 })
        assertTrue("Hardware-locked PRN 31 should be merged", sky.satellites.any { it.name == "G31" && it.hardwareLocked })
        val filtered = sky.withGnssVisibility(false)
        assertFalse(filtered.satellites.any { it.isGnss })
        assertEquals(sky.satellites.filterNot { it.isGnss }, filtered.satellites)
        assertEquals(filtered.satellites.count { it.agency == "EU" }, filtered.euCount)
        assertEquals(filtered.satellites.count { it.agency == "US" }, filtered.usCount)
        assertEquals(0, filtered.hardwareSatCount)
        assertEquals(sky, sky.withGnssVisibility(true))
        assertTrue(sky.satellites.filter { !it.id.startsWith("HW-") }.all {
            it.launchDate != "DESCONOCIDO" && it.inclinationDeg.isFinite() && it.operator != "DESCONOCIDO"
        })
    }

    @Test
    fun satelliteCatalogIncludesRealScienceWeatherAndCommercialOrbits() {
        val catalog = SatelliteTrackerProvider.parseTleCatalog(SatelliteTrackerProvider.SEED_ALMANAC_TLE)
        val requiredNames = listOf("SENTINEL-1A", "SENTINEL-3A", "SENTINEL-3B", "SENTINEL-5P", "SENTINEL-6A",
            "METOP-B", "METOP-C", "CRYOSAT 2", "SMOS", "SWARM A", "NOAA 15", "NOAA 18", "NOAA 19",
            "NOAA 21 (JPSS-2)", "LANDSAT 8", "LANDSAT 9", "TERRA", "AQUA", "SUOMI NPP")
        requiredNames.forEach { name -> assertTrue("Missing $name", catalog.any { it.rawName == name }) }
        val expected = mapOf("STARLINK" to "SPACEX / STARLINK (EE.UU.)", "ICEYE" to "ICEYE (UE / FINLANDIA)",
            "ONEWEB" to "EUTELSAT ONEWEB (UE)", "IRIDIUM" to "IRIDIUM COMM. (EE.UU.)",
            "PLANET" to "PLANET LABS (EE.UU.)", "SPIRE" to "SPIRE GLOBAL (EE.UU.)")
        expected.forEach { (constellation, operator) ->
            val entries = catalog.filter { it.constellation == constellation }
            assertTrue("Missing $constellation", entries.isNotEmpty())
            assertTrue(entries.all { it.operator == operator && it.inclinationDeg > 0 && it.launchDate != "DESCONOCIDO" })
            assertTrue(entries.all { it.agency == if (constellation in listOf("ONEWEB", "ICEYE")) "EU" else "US" })
        }
        assertEquals("1957", SatelliteTrackerProvider.launchDateFor("1 00001U 57001A   26330.00000000"))
        assertEquals("2056", SatelliteTrackerProvider.launchDateFor("1 00001U 56001A   26330.00000000"))
        val tracker = SatelliteTrackerProvider(fetcher = { null })
        val all = tracker.computeOverhead(40.416775, -3.703790, minElevationDeg = -90f)
        val nonGnss = all.withGnssVisibility(false)
        assertTrue(nonGnss.satellites.any { it.agency == "EU" })
        assertTrue(nonGnss.satellites.any { it.agency == "US" })
        assertFalse(nonGnss.satellites.any { it.isGnss })
        expected.keys.forEach { constellation ->
            assertTrue(nonGnss.satellites.count { it.constellation == constellation } <= 12)
        }
        // Repeated group records must not multiply markers or propagation work.
        val duplicated = SatelliteTrackerProvider.parseTleCatalog(SatelliteTrackerProvider.SEED_ALMANAC_TLE.repeat(2))
        assertEquals(catalog.size, duplicated.size)
        val oldCache = java.io.File(folder.root, "old-satellites.txt")
        oldCache.writeText(SatelliteTrackerProvider.SEED_ALMANAC_TLE.lineSequence().take(3).joinToString("\n"))
        val migrated = SatelliteTrackerProvider(cacheFile = oldCache, fetcher = { null })
            .computeOverhead(40.416775, -3.703790, minElevationDeg = -90f).withGnssVisibility(false)
        expected.keys.forEach { constellation ->
            assertTrue("Old cache must gain $constellation", migrated.satellites.any { it.constellation == constellation })
        }
    }

    @Test
    fun deviceGnssLocationOverridesCommuteAndRadarOriginWithFallback() {
        val cfg = AssistantConfig(homeLabel = "CASA", homeLat = 40.415260, homeLon = -3.684500)

        // Without fix -> fallback to config home
        val fallbackOrigin = DeviceGnssProvider.resolveOrigin(cfg, null)
        assertFalse(fallbackOrigin.isHardwareFix)
        assertEquals(40.415260, fallbackOrigin.lat, 1e-6)
        assertEquals(-3.684500, fallbackOrigin.lon, 1e-6)
        assertEquals("CASA", fallbackOrigin.label)

        // With valid hardware GPS fix -> overrides origin for commute & radar
        val gpsFix = DeviceLocationFix(
            lat = 40.416775,
            lon = -3.703790,
            accuracyM = 8f,
            provider = "gps",
            timestampMs = 1_700_000_000_000L
        )
        val liveCfg = DeviceGnssProvider.applyOriginToConfig(cfg, gpsFix)
        assertEquals(40.416775, liveCfg.homeLat, 1e-6)
        assertEquals(-3.703790, liveCfg.homeLon, 1e-6)
        assertEquals("GPS", liveCfg.homeLabel)

        // Verify SyncCoordinator passes live GPS origin to CommuteProvider
        val store = LocalStore(object : ContextWrapper(null) {
            override fun getFilesDir() = folder.root
        })
        store.updateSnapshot { it.copy(deviceLocation = gpsFix) }
        var capturedLat = 0.0
        var capturedLon = 0.0
        val spyCommute = object : CommuteProvider() {
            override fun fetchRoute(config: AssistantConfig, targetName: String, destLat: Double, destLon: Double): CommuteRoute {
                capturedLat = config.homeLat
                capturedLon = config.homeLon
                return CommuteRoute(targetName, config.homeLabel, 15, 9.0, 0, "OSRM CAR", "RUTA BASE", 1_700_000_000_000L)
            }
        }
        val coordinator = SyncCoordinator(store, commuteProvider = spyCommute)
        coordinator.runCommuteSyncBlocking(SyncCoordinator.CommuteSyncTarget.WORK)
        assertEquals(40.416775, capturedLat, 1e-6)
        assertEquals(-3.703790, capturedLon, 1e-6)
    }
}
