package com.javiblzq.assistant.server

import android.content.Context
import com.javiblzq.assistant.data.AssistantConfig
import com.javiblzq.assistant.data.HrZoneConfig
import com.javiblzq.assistant.data.LocalStore
import com.javiblzq.assistant.data.ManualCalendarEvent
import com.javiblzq.assistant.data.QuickTask
import com.javiblzq.assistant.data.SmartBulbDevice
import com.javiblzq.assistant.net.HttpClient
import com.javiblzq.assistant.providers.HarbizClient
import com.javiblzq.assistant.providers.MarketProvider
import com.javiblzq.assistant.providers.SemyraServiceMonitor
import com.javiblzq.assistant.providers.StravaClient
import com.javiblzq.assistant.providers.StravaTokenBundle
import com.javiblzq.assistant.providers.SyncCoordinator
import com.javiblzq.assistant.providers.TapoSmartHomeClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LocalConfigServer(
    private val context: Context,
    private val store: LocalStore,
    private val syncCoordinator: SyncCoordinator,
    private val port: Int = 8080,
    private val onRemoteWake: () -> Unit,
    private val getLanIp: () -> String
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool: ExecutorService = Executors.newFixedThreadPool(4)
    private data class StravaAuthorization(val state: String, val clientId: String, val expiresAtMs: Long)
    private val stravaAuthorizations = ArrayDeque<StravaAuthorization>()

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        acceptThread = Thread {
            try {
                val ss = ServerSocket(port)
                ss.reuseAddress = true
                serverSocket = ss
                while (running) {
                    val client = ss.accept()
                    pool.execute { handleClient(client) }
                }
            } catch (_: Exception) {
            }
        }.apply {
            name = "LocalConfigServer"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        pool.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            val addr = sock.inetAddress
            if (addr == null || (!addr.isSiteLocalAddress && !addr.isLoopbackAddress)) {
                return
            }
            try {
                sock.soTimeout = 15_000
                val input = BufferedInputStream(sock.getInputStream())
                val req = readHttpRequest(input) ?: return
                val resp = route(req)
                val out = sock.getOutputStream()
                val headerBytes = buildString {
                    append("HTTP/1.1 ${resp.status} ${resp.statusText}\r\n")
                    append("Content-Type: ${resp.contentType}\r\n")
                    append("Content-Length: ${resp.body.size}\r\n")
                    append("Cache-Control: no-store\r\n")
                    append("Connection: close\r\n")
                    for ((k, v) in resp.headers) {
                        append("$k: $v\r\n")
                    }
                    append("\r\n")
                }.toByteArray(Charsets.ISO_8859_1)
                out.write(headerBytes)
                if (resp.body.isNotEmpty()) out.write(resp.body)
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    private data class ParsedRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val body: String,
        val contentType: String
    )

    private data class HttpReply(
        val status: Int,
        val statusText: String,
        val contentType: String,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap()
    )

    private fun readHttpRequest(input: BufferedInputStream): ParsedRequest? {
        val headerBuf = ByteArrayOutputStream()
        var prev4 = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null
            headerBuf.write(b)
            prev4 = ((prev4 shl 8) or (b and 0xFF))
            if (prev4 == 0x0D0A0D0A) break
            if (headerBuf.size() > 32 * 1024) return null
        }
        val headerStr = headerBuf.toString("ISO-8859-1")
        val lines = headerStr.split("\r\n")
        if (lines.isEmpty()) return null
        val reqLine = lines[0].split(" ")
        if (reqLine.size < 2) return null

        val method = reqLine[0].uppercase()
        val rawTarget = reqLine[1]
        val qIdx = rawTarget.indexOf('?')
        val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
        val queryStr = if (qIdx >= 0) rawTarget.substring(qIdx + 1) else ""
        val query = parseQueryString(queryStr)

        var contentLength = 0
        var contentType = ""
        for (i in 1 until lines.size) {
            val line = lines[i]
            val c = line.indexOf(':')
            if (c <= 0) continue
            val value = line.substring(c + 1).trim()
            when (line.substring(0, c).trim().lowercase()) {
                "content-length" -> contentLength = value.toIntOrNull() ?: 0
                "content-type" -> contentType = value.substringBefore(';').trim()
            }
        }

        val bodyBytes = if (contentLength in 1..(1024 * 1024)) {
            val buf = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val n = input.read(buf, offset, contentLength - offset)
                if (n <= 0) break
                offset += n
            }
            buf.copyOf(offset)
        } else ByteArray(0)

        return ParsedRequest(method, path, query, String(bodyBytes, Charsets.UTF_8), contentType)
    }

    private fun parseQueryString(qs: String): Map<String, String> {
        if (qs.isBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in qs.split("&")) {
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                map[k] = v
            } else if (pair.isNotEmpty()) {
                map[URLDecoder.decode(pair, "UTF-8")] = ""
            }
        }
        return map
    }

    private fun route(req: ParsedRequest): HttpReply {
        return when {
            req.method == "GET" && req.path == "/" ->
                assetReply("web/index.html", "text/html; charset=utf-8")

req.method == "GET" && req.path == "/fonts/TecnicaA-53.woff2" ->
                assetReply("web/fonts/TecnicaA-53.woff2", "font/woff2")

            req.method == "GET" && req.path == "/fonts/Doto-Bold.ttf" ->
                assetReply("web/fonts/Doto-Bold.ttf", "font/ttf")

            req.method == "GET" && req.path == "/api/state" ->
                handleGetState()

            req.method == "POST" && req.path == "/api/wake" -> {
                onRemoteWake()
                jsonReply(JSONObject().put("ok", true))
            }

            req.method == "POST" && req.path == "/api/sync" -> {
                syncCoordinator.triggerSyncAsync()
                jsonReply(JSONObject().put("ok", true))
            }

            req.method == "POST" && req.path == "/api/commute/sync" -> {
                syncCoordinator.triggerCommuteSyncAsync()
                jsonReply(JSONObject().put("ok", true))
            }

            req.method == "POST" && req.path == "/api/config" ->
                handleUpdateConfig(req.body)

            req.method == "POST" && req.path == "/api/market" ->
                handleJsonPost(req, ::handleMarket)

            req.method == "POST" && req.path == "/api/smarthome/credentials" ->
                handleJsonPost(req, ::handleSmartHomeCredentials)

            req.method == "POST" && req.path == "/api/smarthome/bulb" ->
                handleJsonPost(req, ::handleSmartBulb)

            req.method == "POST" && req.path == "/api/tasks" ->
                handleTasks(req.body)

            req.method == "POST" && req.path == "/api/calendar/manual" ->
                handleManualCalendar(req.body)

            req.method == "POST" && req.path == "/api/harbiz/connect" ->
                handleHarbizConnect(req.body)

            req.method == "GET" && req.path == "/api/strava/authorize" ->
                handleStravaAuthorize(req.query["callback"] == "localhost")

            req.method == "GET" && req.path == "/api/strava/callback" ->
                handleStravaCallback(req.query)

            req.method == "POST" && req.path == "/api/strava/token" ->
                handleStravaManualToken(req.body)

            req.method == "GET" && req.path == "/api/geocode" ->
                handleGeocode(req.query["q"].orEmpty())

            else -> jsonReply(JSONObject().put("error", "Not found"), status = 404, statusText = "Not Found")
        }
    }

    private fun handleGetState(): HttpReply {
        val cfg = store.config
        val snap = store.snapshot
        val summary = JSONObject()
            .put("weatherStatus", snap.weatherStatus)
            .put("weatherTemp", snap.weather?.currentTempC)
            .put("weatherCondition", snap.weather?.conditionText)
            .put("workEtaMin", snap.workCommute?.durationMin)
            .put("workProvider", snap.workCommute?.providerLabel)
            .put("workStatus", snap.workCommute?.statusLabel)
            .put("workFetchedAtMs", snap.workCommute?.fetchedAtMs)
            .put("gymEtaMin", snap.gymCommute?.durationMin)
            .put("gymProvider", snap.gymCommute?.providerLabel)
            .put("gymStatus", snap.gymCommute?.statusLabel)
            .put("gymFetchedAtMs", snap.gymCommute?.fetchedAtMs)
            .put("harbizStatus", snap.harbizStatus)
            .put("harbizWorkoutTitle", snap.todayWorkout?.title ?: snap.latestCompletedWorkout?.title)
            .put("stravaStatus", snap.stravaStatus)
            .put("stravaLastRun", snap.latestRunAnalysis?.activity?.name)
            .put("telegramStatus", snap.telegramStatus)
            .put("calendarCount", snap.calendarEvents.size)
            .put("lastSyncMs", snap.lastSyncMs)

        val root = JSONObject()
            .put("lanIp", getLanIp())
            .put("port", port)
            .put("config", cfg.toJson(redactSecrets = true))
            .put("summary", summary)
            .put("market", snap.market?.toJson() ?: JSONObject.NULL)
            .put("serviceHealth", JSONArray(snap.serviceHealth.map { it.toJson() }))
            .put("smartBulbs", JSONArray(cfg.smartBulbs.map { it.toJson(redactSecrets = true) }))
            .put("smartBulbsStatus", SyncCoordinator.smartBulbsStatus(cfg.smartBulbs))
        return jsonReply(root)
    }

    private fun handleJsonPost(req: ParsedRequest, handler: (JSONObject) -> HttpReply): HttpReply {
        if (!req.contentType.equals("application/json", ignoreCase = true)) {
            return apiError("Se requiere application/json", 415, "Unsupported Media Type")
        }
        val payload = try {
            JSONObject(req.body)
        } catch (_: org.json.JSONException) {
            return apiError("JSON inválido")
        }
        return try {
            handler(payload)
        } catch (e: IllegalArgumentException) {
            apiError(e.message ?: "Datos inválidos")
        } catch (_: Exception) {
            apiError("No se pudo guardar la operación", 500, "Internal Server Error")
        }
    }

    private fun handleMarket(payload: JSONObject): HttpReply {
        val action = payload.optString("action")
        require(action in setOf("add", "remove", "set_primary")) { "Acción de mercado inválida" }
        val symbol = MarketProvider.normalizeSymbol(payload.optString("symbol"))
            ?: return apiError("Ticker inválido")
        val updated = store.updateConfig { cfg ->
            val watchlist = cfg.marketWatchlistSymbols.mapNotNull(MarketProvider::normalizeSymbol).distinct()
            val primary = MarketProvider.normalizeSymbol(cfg.marketPrimarySymbol)
            when (action) {
                "add" -> cfg.copy(marketWatchlistSymbols = if (symbol == primary) watchlist else (watchlist + symbol).distinct())
                "remove" -> cfg.copy(marketWatchlistSymbols = watchlist - symbol)
                else -> cfg.copy(marketPrimarySymbol = symbol, marketWatchlistSymbols = watchlist - symbol)
            }
        }
        syncCoordinator.triggerSyncAsync()
        return configReply(updated)
    }

    private fun handleSmartHomeCredentials(payload: JSONObject): HttpReply {
        val urls = if (payload.has("semyraServiceUrls")) {
            val array = payload.optJSONArray("semyraServiceUrls")
                ?: return apiError("semyraServiceUrls debe ser una lista de URLs")
            for (i in 0 until array.length()) {
                val url = array.opt(i) as? String
                require(url != null && SemyraServiceMonitor.httpUri(url.trim()) != null) { "URL de servicio inválida" }
            }
            AssistantConfig.readStringList(array)
        } else null
        val updated = store.updateConfig { cfg ->
            val password = payload.optStringSafe("tapoPassword", cfg.tapoPassword)
            cfg.copy(
                tapoEmail = payload.optStringSafe("tapoEmail", cfg.tapoEmail).trim(),
                tapoPassword = if (password == "••••••") cfg.tapoPassword else password,
                semyraServiceUrls = urls ?: cfg.semyraServiceUrls
            )
        }
        syncCoordinator.triggerSyncAsync()
        return configReply(updated)
    }

    private fun handleSmartBulb(payload: JSONObject): HttpReply {
        val id = payload.optStringSafe("id", "")
        return when (payload.optString("action")) {
            "add" -> {
                val name = payload.optStringSafe("name", "").trim()
                val ip = payload.optStringSafe("ipAddress", "").trim()
                val onUrl = payload.optStringSafe("webhookOnUrl", "").trim()
                val offUrl = payload.optStringSafe("webhookOffUrl", "").trim()
                require(name.isNotEmpty()) { "Indica un nombre para la bombilla" }
                require(ip.isNotEmpty() || onUrl.isNotEmpty() || offUrl.isNotEmpty()) { "Indica una IP TAPO o un webhook" }
                require(ip.isEmpty() || TapoSmartHomeClient.isLanAddress(ip)) { "IP TAPO inválida" }
                require(listOf(onUrl, offUrl).all { it.isEmpty() || SemyraServiceMonitor.httpUri(it) != null }) {
                    "URL de webhook inválida"
                }
                val bulb = SmartBulbDevice(UUID.randomUUID().toString(), name, ip, webhookOnUrl = onUrl, webhookOffUrl = offUrl)
                val updated = store.updateConfig { it.copy(smartBulbs = it.smartBulbs + bulb) }
                syncCoordinator.triggerSyncAsync()
                configReply(updated)
            }
            "delete" -> {
                val updated = store.updateConfig { cfg ->
                    require(cfg.smartBulbs.any { it.id == id }) { "Bombilla desconocida" }
                    cfg.copy(smartBulbs = cfg.smartBulbs.filterNot { it.id == id })
                }
                syncCoordinator.triggerSyncAsync()
                configReply(updated)
            }
            "toggle", "set" -> {
                val on = payload.opt("on") as? Boolean
                require(on != null || (payload.optString("action") == "toggle" && !payload.has("on"))) {
                    "on debe ser un booleano"
                }
                val brightness = if (payload.has("brightness")) {
                    val value = payload.optDouble("brightness")
                    require(value in 1.0..100.0 && value == value.toInt().toDouble()) { "Brillo fuera de rango" }
                    value.toInt()
                } else null
                val (ok, status) = syncCoordinator.setSmartBulb(id, on, brightness)
                    ?: return apiError("Bombilla desconocida", 404, "Not Found")
                if (ok) jsonReply(JSONObject().put("ok", true).put("statusText", status)
                    .put("config", store.config.toJson(redactSecrets = true)))
                else apiError(status, 502, "Bad Gateway")
            }
            else -> apiError("Acción de bombilla inválida")
        }
    }

    private fun configReply(config: AssistantConfig): HttpReply =
        jsonReply(JSONObject().put("ok", true).put("config", config.toJson(redactSecrets = true)))

    private fun apiError(message: String, status: Int = 400, label: String = "Bad Request"): HttpReply =
        jsonReply(JSONObject().put("ok", false).put("error", message), status, label)

    private fun handleUpdateConfig(body: String): HttpReply {
        return try {
            val payload = JSONObject(body)
            val updated = store.updateConfig { cur ->
                val newGoogleKey = payload.optStringSafe("googleMapsApiKey", cur.googleMapsApiKey).trim()
                val newTomTomKey = payload.optStringSafe("tomtomApiKey", cur.tomtomApiKey).trim()
                val newStravaSecret = payload.optStringSafe("stravaClientSecret", cur.stravaClientSecret)
                val newTelegramToken = payload.optStringSafe("telegramBotToken", cur.telegramBotToken).trim()

                val icals = if (payload.has("icalUrls") && !payload.isNull("icalUrls")) {
                    val arr = payload.getJSONArray("icalUrls")
                    (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf(String::isNotEmpty) }
                } else cur.icalUrls

                val zones = if (payload.has("hrZones") && !payload.isNull("hrZones")) {
                    HrZoneConfig.fromJsonArray(payload.getJSONArray("hrZones"))
                } else cur.hrZones

                cur.copy(
                    deviceName = payload.optStringSafe("deviceName", cur.deviceName),
                    timezone = payload.optStringSafe("timezone", cur.timezone),
                    dayStartHour = payload.optIntSafe("dayStartHour", cur.dayStartHour).coerceIn(0, 23),
                    dayEndHour = payload.optIntSafe("dayEndHour", cur.dayEndHour).coerceIn(0, 23),
                    wakeTimeoutSeconds = payload.optIntSafe("wakeTimeoutSeconds", cur.wakeTimeoutSeconds).coerceIn(10, 600),
                    nightWakeTimeoutSeconds = payload.optIntSafe("nightWakeTimeoutSeconds", cur.nightWakeTimeoutSeconds).coerceIn(10, 300),
                    lowPowerBrightness = payload.optDoubleSafe("lowPowerBrightness", cur.lowPowerBrightness.toDouble()).toFloat().coerceIn(0.01f, 0.5f),
                    activeBrightness = payload.optDoubleSafe("activeBrightness", cur.activeBrightness.toDouble()).toFloat().coerceIn(0.2f, 1.0f),

                    homeLabel = payload.optStringSafe("homeLabel", cur.homeLabel),
                    homeLat = payload.optDoubleSafe("homeLat", cur.homeLat),
                    homeLon = payload.optDoubleSafe("homeLon", cur.homeLon),
                    workLabel = payload.optStringSafe("workLabel", cur.workLabel),
                    workLat = payload.optDoubleSafe("workLat", cur.workLat),
                    workLon = payload.optDoubleSafe("workLon", cur.workLon),
                    gymLabel = payload.optStringSafe("gymLabel", cur.gymLabel),
                    gymLat = payload.optDoubleSafe("gymLat", cur.gymLat),
                    gymLon = payload.optDoubleSafe("gymLon", cur.gymLon),
                    trafficProvider = payload.optStringSafe("trafficProvider", cur.trafficProvider),
                    trafficIntervalMinutes = payload.optIntSafe("trafficIntervalMinutes", cur.trafficIntervalMinutes).coerceIn(1, 60),
                    googleMapsApiKey = if (newGoogleKey == "••••••") cur.googleMapsApiKey else newGoogleKey,
                    tomtomApiKey = if (newTomTomKey == "••••••") cur.tomtomApiKey else newTomTomKey,

                    stravaClientId = payload.optStringSafe("stravaClientId", cur.stravaClientId),
                    stravaClientSecret = if (newStravaSecret == "••••••") cur.stravaClientSecret else newStravaSecret,
                    hrZones = zones,

                    weeklyTrainingGoal = payload.optIntSafe("weeklyTrainingGoal", cur.weeklyTrainingGoal).coerceIn(1, 30),
                    telegramBotToken = if (newTelegramToken == "••••••") cur.telegramBotToken else newTelegramToken,
                    telegramAllowedChatId = payload.optStringSafe("telegramAllowedChatId", cur.telegramAllowedChatId).trim(),

                    semyraApiUrl = payload.optStringSafe("semyraApiUrl", cur.semyraApiUrl),
                    icalUrls = icals,
                    syncIntervalMinutes = payload.optIntSafe("syncIntervalMinutes", cur.syncIntervalMinutes).coerceIn(5, 120)
                )
            }
            val trafficKeys = listOf(
                "trafficProvider", "trafficIntervalMinutes", "googleMapsApiKey", "tomtomApiKey",
                "homeLat", "homeLon", "workLat", "workLon", "gymLat", "gymLon"
            )
            if (trafficKeys.any { payload.has(it) }) {
                syncCoordinator.triggerCommuteSyncAsync()
            }
            syncCoordinator.triggerSyncAsync()
            jsonReply(JSONObject().put("ok", true).put("config", updated.toJson(redactSecrets = true)))
        } catch (e: Exception) {
            jsonReply(JSONObject().put("ok", false).put("error", e.message), 400, "Bad Request")
        }
    }

    private fun JSONObject.optStringSafe(key: String, fallback: String): String =
        if (!has(key) || isNull(key)) fallback else optString(key, fallback)

    private fun JSONObject.optIntSafe(key: String, fallback: Int): Int =
        if (!has(key) || isNull(key)) fallback else optInt(key, fallback)

    private fun JSONObject.optDoubleSafe(key: String, fallback: Double): Double {
        if (!has(key) || isNull(key)) return fallback
        val v = optDouble(key, fallback)
        return if (v.isNaN() || v.isInfinite()) fallback else v
    }

    private fun handleTasks(body: String): HttpReply {
        return try {
            val p = JSONObject(body)
            val action = p.optString("action", "note")
            val updated = store.updateConfig { cur ->
                when (action) {
                    "add" -> {
                        val text = p.optString("text", "").trim()
                        val priority = p.optBoolean("priority", false)
                        val urgency = QuickTask.normalizeUrgency(p.optString("urgency", ""), priority)
                        val dueDate = p.optString("dueDate", "").trim()
                        if (text.isEmpty()) cur else cur.copy(
                            tasks = listOf(
                                QuickTask(
                                    id = "t_${System.currentTimeMillis()}",
                                    text = text,
                                    done = false,
                                    priority = priority || urgency == QuickTask.URGENCY_HIGH,
                                    dueDate = dueDate,
                                    urgency = urgency
                                )
                            ) + cur.tasks
                        )
                    }
                    "toggle" -> {
                        val id = p.optString("id", "")
                        cur.copy(tasks = cur.tasks.map { if (it.id == id) it.copy(done = !it.done) else it })
                    }
                    "delete" -> {
                        val id = p.optString("id", "")
                        cur.copy(tasks = cur.tasks.filterNot { it.id == id })
                    }
                    "note" -> cur.copy(pinnedNote = p.optString("pinnedNote", ""))
                    else -> cur
                }
            }
            jsonReply(JSONObject().put("ok", true).put("config", updated.toJson(redactSecrets = true)))
        } catch (e: Exception) {
            jsonReply(JSONObject().put("ok", false).put("error", e.message), 400, "Bad Request")
        }
    }

    private fun handleManualCalendar(body: String): HttpReply {
        return try {
            val p = JSONObject(body)
            val action = p.optString("action", "add")
            val updated = store.updateConfig { cur ->
                when (action) {
                    "add" -> {
                        val ev = ManualCalendarEvent(
                            id = "ev_${System.currentTimeMillis()}",
                            title = p.optString("title", "").trim(),
                            dateIso = p.optString("dateIso", "").trim(),
                            startTime = p.optString("startTime", "09:00").trim(),
                            endTime = p.optString("endTime", "").trim(),
                            location = p.optString("location", "").trim()
                        )
                        if (ev.title.isEmpty() || ev.dateIso.isEmpty()) cur
                        else cur.copy(manualEvents = cur.manualEvents + ev)
                    }
                    "delete" -> {
                        val id = p.optString("id", "")
                        cur.copy(manualEvents = cur.manualEvents.filterNot { it.id == id })
                    }
                    else -> cur
                }
            }
            syncCoordinator.triggerSyncAsync()
            jsonReply(JSONObject().put("ok", true).put("config", updated.toJson(redactSecrets = true)))
        } catch (e: Exception) {
            jsonReply(JSONObject().put("ok", false).put("error", e.message), 400, "Bad Request")
        }
    }

    private fun handleHarbizConnect(body: String): HttpReply {
        return try {
            val p = JSONObject(body)
            val email = p.optString("email", "").trim()
            val password = p.optString("password", "")
            val token = p.optString("token", "").trim()

            val client = HarbizClient()
            val res = when {
                token.isNotEmpty() -> client.connectWithToken(token)
                email.isNotEmpty() && password.isNotEmpty() -> client.connectWithCredentials(email, password)
                else -> return jsonReply(
                    JSONObject().put("ok", false).put("error", "Indica email+password o token"),
                    400, "Bad Request"
                )
            }
            if (!res.ok || res.session == null) {
                store.updateSnapshot { it.copy(harbizStatus = res.message) }
                val error = JSONObject().put("ok", false).put("error", res.message)
                return if (res.authenticationRejected) jsonReply(error, 401, "Unauthorized")
                    else jsonReply(error, 502, "Bad Gateway")
            }

            store.updateConfig { cur ->
                cur.copy(
                    harbizEmail = email.ifBlank { cur.harbizEmail },
                    harbizToken = res.session.token,
                    harbizAccountId = res.session.accountId,
                    harbizTokenExpiresAt = res.session.expiresAt
                )
            }
            store.updateSnapshot { it.copy(harbizStatus = res.message) }
            syncCoordinator.triggerSyncAsync()
            jsonReply(JSONObject().put("ok", true).put("message", res.message))
        } catch (e: Exception) {
            val message = "Harbiz: no se pudo guardar la sesión."
            store.updateSnapshot { it.copy(harbizStatus = message) }
            jsonReply(JSONObject().put("ok", false).put("error", message), 500, "Error")
        }
    }

    private fun handleStravaAuthorize(useLocalhost: Boolean): HttpReply {
        val cfg = store.config
        if (cfg.stravaClientId.isBlank() || cfg.stravaClientSecret.isBlank()) {
            return HttpReply(
                status = 400,
                statusText = "Bad Request",
                contentType = "text/plain; charset=utf-8",
                body = "Guarda primero tu Strava Client ID y Client Secret en el portal.".toByteArray()
            )
        }
        val callbackHost = if (useLocalhost) "localhost" else getLanIp()
        val callback = "http://$callbackHost:$port/api/strava/callback"
        val state = UUID.randomUUID().toString()
        synchronized(stravaAuthorizations) {
            if (stravaAuthorizations.size >= 16) stravaAuthorizations.removeFirst()
            stravaAuthorizations.addLast(StravaAuthorization(state, cfg.stravaClientId, System.currentTimeMillis() + 15 * 60_000L))
        }
        val url = "https://www.strava.com/oauth/authorize" +
            "?client_id=${URLEncoder.encode(cfg.stravaClientId.trim(), "UTF-8")}" +
            "&redirect_uri=${URLEncoder.encode(callback, "UTF-8")}" +
            "&response_type=code&approval_prompt=force&scope=activity:read_all&state=$state"
        return redirect(url)
    }

    private fun handleStravaCallback(query: Map<String, String>): HttpReply {
        val authorization = synchronized(stravaAuthorizations) {
            stravaAuthorizations.firstOrNull { it.state == query["state"] }
                ?.also { stravaAuthorizations.remove(it) }
        }
        val cfg = store.config
        if (authorization == null || authorization.clientId != cfg.stravaClientId ||
            authorization.expiresAtMs <= System.currentTimeMillis()
        ) {
            return stravaCallbackError("Strava: autorización caducada o inválida. Empieza de nuevo en el portal.")
        }
        val code = query["code"]
        val error = try {
            when {
                query["error"] == "access_denied" -> "Autorización de Strava cancelada."
                code.isNullOrBlank() -> "Strava no devolvió un código de autorización."
                !StravaClient.hasActivityScope(query["scope"].orEmpty()) -> StravaClient.ACTIVITY_PERMISSION_REQUIRED
                else -> {
                    val (bundle, message) = StravaClient().exchangeAuthorizationCode(cfg.stravaClientId, cfg.stravaClientSecret, code)
                    if (bundle != null) {
                        saveStravaAuthorization(cfg.stravaClientId, cfg.stravaClientSecret, bundle)
                        return redirect("/?strava=connected")
                    }
                    message
                }
            }
        } catch (_: Exception) {
            "Strava: no se pudo guardar la autorización."
        }
        store.updateSnapshot { it.copy(stravaStatus = error) }
        return stravaCallbackError(error)
    }

    private fun stravaCallbackError(message: String): HttpReply =
        redirect("/?strava=error&reason=${URLEncoder.encode(message, "UTF-8")}")

    private fun redirect(location: String): HttpReply = HttpReply(
        status = 302,
        statusText = "Found",
        contentType = "text/plain; charset=utf-8",
        body = ByteArray(0),
        headers = mapOf("Location" to location)
    )

    private fun saveStravaAuthorization(clientId: String, clientSecret: String, bundle: StravaTokenBundle) {
        store.updateConfig { cur ->
            cur.copy(
                stravaClientId = clientId,
                stravaClientSecret = clientSecret,
                stravaAccessToken = bundle.accessToken,
                stravaRefreshToken = bundle.refreshToken,
                stravaExpiresAt = bundle.expiresAt,
                stravaAthleteName = bundle.athleteName
            )
        }
        store.updateSnapshot { it.copy(stravaStatus = "CONECTADO") }
        syncCoordinator.triggerSyncAsync()
    }

    private fun handleStravaManualToken(body: String): HttpReply {
        return try {
            val p = JSONObject(body)
            val cfg = store.config
            val clientId = p.optString("clientId", "").trim().ifBlank { cfg.stravaClientId }
            val suppliedSecret = p.optString("clientSecret", "").trim().takeUnless { it == "••••••" }.orEmpty()
            val clientSecret = suppliedSecret.ifBlank { cfg.stravaClientSecret.takeIf { clientId == cfg.stravaClientId }.orEmpty() }
            val refresh = p.optString("refreshToken", "").trim()
            val access = p.optString("accessToken", "").trim()
            val codeInput = p.optString("authorizationCode", "").trim()
            if (codeInput.isNotBlank()) {
                StravaClient.callbackParameter(codeInput, "scope")?.let { scope ->
                    if (!StravaClient.hasActivityScope(scope)) return stravaManualError(StravaClient.ACTIVITY_PERMISSION_REQUIRED)
                }
                if (StravaClient.callbackParameter(codeInput, "error") == "access_denied") {
                    return stravaManualError("Autorización de Strava cancelada.")
                }
                val code = StravaClient.authorizationCodeFrom(codeInput)
                    ?: return stravaManualError("Pega la URL de retorno o un código de autorización de Strava.")
                val (bundle, message) = StravaClient().exchangeAuthorizationCode(clientId, clientSecret, code)
                if (bundle == null) return stravaManualError(message)
                saveStravaAuthorization(clientId, clientSecret, bundle)
                return jsonReply(JSONObject().put("ok", true).put("message", "STRAVA CONECTADO"))
            }
            if (refresh.isBlank() && access.isBlank()) return stravaManualError("Indica un token o código de Strava.")
            if (refresh.isNotBlank() && (clientId.isBlank() || clientSecret.isBlank())) {
                return stravaManualError("Indica el Client ID y Client Secret para usar un refresh token.")
            }
            store.updateConfig { cur ->
                cur.copy(
                    stravaClientId = clientId,
                    stravaClientSecret = clientSecret,
                    stravaRefreshToken = refresh,
                    stravaAccessToken = access,
                    stravaExpiresAt = 0L,
                    stravaAthleteName = ""
                )
            }
            store.updateSnapshot { it.copy(stravaStatus = "SINCRONIZANDO") }
            syncCoordinator.triggerSyncAsync()
            jsonReply(JSONObject().put("ok", true).put("message", "TOKEN STRAVA GUARDADO"))
        } catch (_: Exception) {
            stravaManualError("Strava: no se pudo guardar la conexión.")
        }
    }

    private fun stravaManualError(message: String): HttpReply {
        store.updateSnapshot { it.copy(stravaStatus = message) }
        return jsonReply(JSONObject().put("ok", false).put("error", message), 400, "Bad Request")
    }

    private fun handleGeocode(query: String): HttpReply {
        if (query.isBlank()) {
            return jsonReply(JSONObject().put("results", JSONArray()))
        }
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=5&q=${URLEncoder.encode(query, "UTF-8")}"
        val resp = HttpClient.get(url)
        if (!resp.isSuccess) {
            return jsonReply(JSONObject().put("results", JSONArray()))
        }
        return try {
            val arr = JSONArray(resp.body)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                out.put(
                    JSONObject()
                        .put("label", item.optString("display_name"))
                        .put("lat", item.optString("lat").toDoubleOrNull() ?: 0.0)
                        .put("lon", item.optString("lon").toDoubleOrNull() ?: 0.0)
                )
            }
            jsonReply(JSONObject().put("results", out))
        } catch (_: Exception) {
            jsonReply(JSONObject().put("results", JSONArray()))
        }
    }

    private fun assetReply(assetPath: String, contentType: String): HttpReply {
        return try {
            val bytes = context.assets.open(assetPath).use(InputStream::readBytes)
            HttpReply(200, "OK", contentType, bytes)
        } catch (_: Exception) {
            HttpReply(404, "Not Found", "text/plain", "Missing asset $assetPath".toByteArray())
        }
    }

    private fun jsonReply(obj: JSONObject, status: Int = 200, statusText: String = "OK"): HttpReply {
        return HttpReply(status, statusText, "application/json; charset=utf-8", obj.toString().toByteArray(Charsets.UTF_8))
    }
}
