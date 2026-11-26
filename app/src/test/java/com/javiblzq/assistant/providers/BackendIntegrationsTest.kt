package com.javiblzq.assistant.providers

import com.javiblzq.assistant.data.AssistantConfig
import com.javiblzq.assistant.data.SmartBulbDevice
import com.javiblzq.assistant.data.StravaActivitySummary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendIntegrationsTest {
    @Test fun harbizMergesCardioSummaryWithSparseDetailAndExtractsCleanSubtitle() {
        val calendar = JSONArray(
            """
            [
              {"_id":"ignored","type":"appointment","date":"2026-11-24T18:00:00Z"},
              {"_id":"run","type":"cardio","title":"Rodaje suave","date":"2026-11-24T08:00:00Z",
                "description":"<p>40&#39; &nbsp; Z2</p>","completed":true},
              {"_id":"trail","type":"activity","title":"Trail","date":"2026-11-25T08:00:00Z"},
              {"_id":"removed","type":"cardio","removed":true,"date":"2026-11-24T08:00:00Z"}
            ]
            """.trimIndent()
        )
        val discovered = HarbizParser.discoverWorkoutEvents(calendar)
        assertEquals(listOf("trail", "run"), discovered.map { it.id })
        val workout = HarbizParser.parseWorkoutDetail(
            JSONObject("""{"_id":"run","type":"workout","workout":{"exercises":[]}}"""),
            summary = discovered.last()
        )!!
        assertEquals("cardio", workout.eventType)
        assertEquals("Rodaje suave", workout.title)
        assertEquals("40' Z2", workout.summarySubtitle)
        assertTrue(workout.isCardioOrRun)
        assertTrue(workout.completed)
    }

    @Test fun harbizDetectsRunningExercisesAndPreservesWeightedStrength() {
        val raw = JSONObject(
            """
            {"_id":"run","type":"workout","title":"Sesión aeróbica","date":"2026-11-24T08:00:00Z",
              "workout":{"exercises":[{"type":"simple","name_provisional":"Carrera",
                "distanceM":10000,"durationS":3000}]}}
            """.trimIndent()
        )
        val run = HarbizParser.parseWorkoutDetail(raw)!!
        assertTrue(run.isCardioOrRun)
        assertEquals("workout", run.eventType)
        assertEquals("10 KM · 50'", run.summarySubtitle)
        val exercises = raw.getJSONObject("workout").getJSONArray("exercises")
        exercises.put(JSONObject("""{"type":"simple","name_provisional":"Sentadilla",
          "result":{"sets":[{"weightKg":45,"reps":5}]}}"""))
        assertFalse(HarbizParser.parseWorkoutDetail(raw)!!.isCardioOrRun)
    }

    @Test fun harbizPrefersLiveCardioTextAndFallsBackToRecordedTargetTime() {
        val summary = JSONObject(
            """
            {"_id":"run","type":"cardio","title":"Sesión aeróbica","date":{"${'$'}date":1795507200000},
              "cardioText":"<div>40’ &nbsp; Z2</div>","completed":true,
              "resultCardio":{"comments":"Entreno completado","target":{"time":{"hours":"","minutes":"42","seconds":"30"}}}}
            """.trimIndent()
        )
        val discovered = HarbizParser.discoverWorkoutEvents(JSONArray().put(summary)).single()
        assertEquals("40' Z2", HarbizParser.parseWorkoutDetail(JSONObject(), summary = discovered)?.summarySubtitle)
        summary.put("cardioText", "")
        assertEquals("42'", HarbizParser.parseWorkoutDetail(summary)?.summarySubtitle)
    }

    @Test fun agendaDeduplicatesSameDayRunAndLabelsNearestUpcomingSession() {
        val summary = JSONObject(
            """{"_id":"run","type":"cardio","date":"2026-11-24T08:00:00Z",
                "title":"Rodaje suave","cardio":{"durationS":2400,"zone":"Z2"},"completed":true}"""
        )
        val today = "2026-11-24"
        val run = HarbizParser.parseWorkoutDetail(summary)!!
        val strength = run.copy(id = "gym", title = "Fuerza", isCardioOrRun = false)
        val activity = StravaActivitySummary(
            1L, "Rodaje suave", "Run", "${today}T08:00:00", today,
            8_000.0, 2400, 2400, 0.0, 3.3, 140.0, null, null
        )
        val cfg = AssistantConfig()
        val schedule = listOf(strength, run)
        val harbizOnly = SyncCoordinator.buildTodayTrainings(today, schedule, null, emptyList(), null, cfg)
        assertEquals(listOf("STRENGTH", "RUN"), harbizOnly.map { it.type })
        assertEquals("40' Z2", harbizOnly.last().subtitle)
        val withStrava = SyncCoordinator.buildTodayTrainings(today, schedule, null, listOf(activity), null, cfg)
        assertEquals(listOf("FUERZA", "RODAJE SUAVE"), withStrava.map { it.title })
        val future = listOf(
            strength.copy(scheduledDateIso = "2026-11-26", scheduledAtMs = run.scheduledAtMs + 172_800_000, completed = false),
            run.copy(scheduledDateIso = "2026-11-25", scheduledAtMs = run.scheduledAtMs + 86_400_000, completed = false)
        )
        val upcoming = SyncCoordinator.buildTodayTrainings(today, future, null, emptyList(), null, cfg)
        assertEquals("2026-11-25 · RODAJE SUAVE", upcoming.single().title)
        val week = StravaAnalyzer.buildWeekSummary(emptyList(), listOf(run), cfg.timezone, run.scheduledAtMs)
        val day = week.days.single { it.dateIso == today }
        assertTrue(day.hasRun)
        assertFalse(day.hasStrength)
        assertEquals(1, week.runningCount)
        val mergedWeek = StravaAnalyzer.buildWeekSummary(listOf(activity), listOf(run), cfg.timezone, run.scheduledAtMs)
        assertEquals(1, mergedWeek.runningCount)
    }

    @Test fun marketParsesMonthlyHistoryAndDoesNotUseMonthlyReferenceForDailyChange() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"IWDA.AS","longName":"MSCI World","currency":"EUR",
              "regularMarketPrice":110,"chartPreviousClose":100},
              "indicators":{"quote":[{"close":[101,null,105,110]}]}}],"error":null}}
        """.trimIndent()
        val quote = MarketProvider.parseQuote("IWDA.AS", body, fetchedAtMs = 123)!!
        assertEquals("IWDA.AS", quote.symbol)
        assertEquals("MSCI World", quote.displayName)
        assertEquals("EUR", quote.currency)
        assertEquals(listOf(101.0, 105.0, 110.0), quote.sparklineCloses)
        assertEquals(110.0, quote.price, 0.0)
        assertEquals(4.76190, quote.changePercent1d, 0.00001)
        assertEquals(10.0, quote.changePercent1m, 0.00001)
        assertEquals(123L, quote.fetchedAtMs)
        assertNull(MarketProvider.parseQuote("BAD", """{"chart":{"error":{"code":"Not Found"}}}"""))
    }

    @Test fun tapoKlapHashesAndSessionKeysMatchIndependentVectors() {
        val email = "fixture@example.com"
        val password = "example-password"
        val v1 = TapoSmartHomeClient.klapAuthHash(email, password, v2 = false)
        val v2 = TapoSmartHomeClient.klapAuthHash(email, password, v2 = true)
        assertEquals("a5f452b83257a7399fe50f5d09eb090a", hex(v1))
        assertEquals("5d2ac01c611b85587d5d94533c1f2f960a2d37ddaecafc11f6b76ac05d2150dc", hex(v2))
        val keys = TapoSmartHomeClient.deriveKlapKeys(ByteArray(16) { it.toByte() }, ByteArray(16) { (it + 16).toByte() }, v2)
        assertEquals("2b1e419fa0027f1505adc39f45abe6f5", hex(keys.key))
        assertEquals("496d3268fd98feba19e1bd7f", hex(keys.ivPrefix))
        assertEquals(-582378058, keys.initialSequence)
        assertEquals("77fc3235bde183a5e693dc0c39fdfd78e90f615409022a9bb155a454", hex(keys.signaturePrefix))
    }

    @Test fun tapoCommandAndConfigRedactionPreservePersistedValues() {
        val on = TapoSmartHomeClient.setCommand(true, 35)
        assertEquals("set_device_info", on.getString("method"))
        assertTrue(on.getJSONObject("params").getBoolean("device_on"))
        assertEquals(35, on.getJSONObject("params").getInt("brightness"))
        val off = TapoSmartHomeClient.setCommand(false, 35).getJSONObject("params")
        assertFalse(off.getBoolean("device_on"))
        assertFalse(off.has("brightness"))

        val cfg = AssistantConfig(
            marketWatchlistSymbols = emptyList(), semyraServiceUrls = listOf("https://example.com/health"),
            tapoEmail = "fixture@example.com", tapoPassword = "example-password",
            smartBulbs = listOf(SmartBulbDevice("fixture", "Lamp", webhookOnUrl = "https://example.com/token"))
        )
        assertEquals(cfg, AssistantConfig.fromJson(cfg.toJson()))
        val public = cfg.toJson(redactSecrets = true)
        assertEquals("••••••", public.getString("tapoPassword"))
        assertEquals("••••••", public.getJSONArray("smartBulbs").getJSONObject(0).getString("webhookOnUrl"))
        val defaults = AssistantConfig.fromJson(JSONObject())
        assertEquals("IWDA.AS", defaults.marketPrimarySymbol)
        assertEquals(listOf("AAPL", "MSFT", "NVDA"), defaults.marketWatchlistSymbols)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
