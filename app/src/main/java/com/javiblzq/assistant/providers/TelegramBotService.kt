package com.javiblzq.assistant.providers

import android.util.Log
import com.javiblzq.assistant.data.CalendarEventItem
import com.javiblzq.assistant.data.LocalStore
import com.javiblzq.assistant.data.ManualCalendarEvent
import com.javiblzq.assistant.data.QuickTask
import com.javiblzq.assistant.net.HttpClient
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface TelegramTransport {
    fun sendMessage(token: String, chatId: String, text: String): Boolean
    fun sendDocument(
        token: String,
        chatId: String,
        filename: String,
        bytes: ByteArray,
        caption: String
    ): Boolean
    fun downloadFileText(token: String, fileId: String): String? = null
}

object HttpTelegramTransport : TelegramTransport {
    private const val TAG = "TelegramBotService"

    override fun sendMessage(token: String, chatId: String, text: String): Boolean {
        if (token.isBlank() || chatId.isBlank()) return false
        val url = "https://api.telegram.org/bot$token/sendMessage"
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
            .toString()
        val resp = HttpClient.postJson(url, payload, timeoutMs = 15_000)
        if (!resp.isSuccess) {
            Log.w(TAG, "sendMessage failed code=${resp.code} err=${resp.error}")
        }
        return resp.isSuccess
    }

    override fun sendDocument(
        token: String,
        chatId: String,
        filename: String,
        bytes: ByteArray,
        caption: String
    ): Boolean {
        if (token.isBlank() || chatId.isBlank()) return false
        val url = "https://api.telegram.org/bot$token/sendDocument"
        val fields = linkedMapOf(
            "chat_id" to chatId,
            "caption" to caption
        )
        val resp = HttpClient.postMultipart(
            url = url,
            fields = fields,
            fileFieldName = "document",
            fileName = filename,
            fileContentType = "text/calendar; charset=utf-8",
            fileBytes = bytes,
            timeoutMs = 20_000
        )
        if (!resp.isSuccess) {
            Log.w(TAG, "sendDocument failed code=${resp.code} err=${resp.error}")
        }
        return resp.isSuccess
    }

    override fun downloadFileText(token: String, fileId: String): String? {
        if (token.isBlank() || fileId.isBlank()) return null
        return try {
            val encodedId = URLEncoder.encode(fileId, "UTF-8")
            val getFileUrl = "https://api.telegram.org/bot$token/getFile?file_id=$encodedId"
            val metaResp = HttpClient.get(getFileUrl, timeoutMs = 15_000)
            if (!metaResp.isSuccess) {
                Log.w(TAG, "getFile failed code=${metaResp.code} err=${metaResp.error}")
                return null
            }
            val root = JSONObject(metaResp.body)
            if (!root.optBoolean("ok", false)) {
                Log.w(TAG, "getFile ok=false")
                return null
            }
            val filePath = root.optJSONObject("result")?.optString("file_path", "")?.trim().orEmpty()
            if (filePath.isEmpty()) {
                Log.w(TAG, "getFile returned empty file_path")
                return null
            }
            val downloadUrl = "https://api.telegram.org/file/bot$token/$filePath"
            val fileResp = HttpClient.get(downloadUrl, timeoutMs = 20_000)
            if (!fileResp.isSuccess) {
                Log.w(TAG, "downloadFile failed code=${fileResp.code} err=${fileResp.error}")
                return null
            }
            fileResp.body
        } catch (e: Exception) {
            Log.w(TAG, "downloadFileText error: ${e.message}")
            null
        }
    }
}

class TelegramBotService(
    private val store: LocalStore,
    private val onMessageProcessed: () -> Unit = {},
    private val onCalendarUpdated: () -> Unit = {},
    private val transport: TelegramTransport = HttpTelegramTransport
) {
    internal enum class BotStep {
        IDLE,
        AWAITING_NEW_CHOICE,
        AWAITING_RECORD_TEXT,
        AWAITING_RECORD_DATE,
        AWAITING_RECORD_URGENCY,
        AWAITING_AGENDA_TAG,
        AWAITING_AGENDA_TITLE,
        AWAITING_AGENDA_DATE,
        AWAITING_AGENDA_TIME
    }

    internal data class ChatSession(
        val step: BotStep = BotStep.IDLE,
        val draftText: String = "",
        val draftDate: String = "",
        val draftTag: String = "",
        val draftTitle: String = ""
    )

    @Volatile
    private var running = false
    private var workerThread: Thread? = null
    private val lifecycleLock = Any()
    private val sessions = ConcurrentHashMap<String, ChatSession>()

    fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            running = true
            workerThread = Thread({ pollLoop() }, "TelegramBotService").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            running = false
            workerThread?.interrupt()
            workerThread = null
        }
    }

    internal fun currentStep(chatId: String): BotStep =
        sessions[chatId]?.step ?: BotStep.IDLE

    private fun pollLoop() {
        while (running && !Thread.currentThread().isInterrupted) {
            val cfg = store.config
            val token = cfg.telegramBotToken.trim()
            if (token.isEmpty()) {
                if (store.snapshot.telegramStatus != "SIN TOKEN") {
                    store.updateSnapshot { it.copy(telegramStatus = "SIN TOKEN") }
                }
                if (!sleepQuietly(IDLE_POLL_SLEEP_MS)) break
                continue
            }

            val offset = if (cfg.telegramLastUpdateId > 0L) cfg.telegramLastUpdateId + 1L else 0L
            val url = "https://api.telegram.org/bot$token/getUpdates?offset=$offset&timeout=$LONG_POLL_TIMEOUT_SEC"
            val resp = HttpClient.get(url, timeoutMs = HTTP_TIMEOUT_MS)
            if (!running || Thread.currentThread().isInterrupted) break

            if (!resp.isSuccess) {
                val errLabel = if (resp.code > 0) "BOT ERR ${resp.code}" else "BOT SIN RED"
                store.updateSnapshot { it.copy(telegramStatus = errLabel) }
                if (!sleepQuietly(ERROR_BACKOFF_MS)) break
                continue
            }

            try {
                processUpdatesJson(resp.body)
            } catch (_: Exception) {
                store.updateSnapshot { it.copy(telegramStatus = "BOT PARSE ERR") }
                if (!sleepQuietly(ERROR_BACKOFF_MS)) break
            }
        }
    }

    internal fun processUpdatesJson(body: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val root = JSONObject(body)
        if (!root.optBoolean("ok", false)) {
            store.updateSnapshot { it.copy(telegramStatus = "BOT API ERR") }
            return false
        }

        val results = root.optJSONArray("result")
        var maxUpdateId = store.config.telegramLastUpdateId
        var processedAny = false
        val token = store.config.telegramBotToken.trim()

        if (results != null) {
            val allowedIds = parseAllowedChatIds(store.config.telegramAllowedChatId)
            for (i in 0 until results.length()) {
                val update = results.optJSONObject(i) ?: continue
                val updateId = update.optLong("update_id", -1L)
                if (updateId > maxUpdateId) {
                    maxUpdateId = updateId
                }

                val msg = update.optJSONObject("message")
                    ?: update.optJSONObject("edited_message")
                    ?: continue
                val chatId = msg.optJSONObject("chat")?.opt("id")?.toString()?.trim().orEmpty()
                if (allowedIds.isNotEmpty() && chatId !in allowedIds) {
                    continue
                }

                val doc = msg.optJSONObject("document")
                if (doc != null && isIcsDocument(doc)) {
                    val fileId = doc.optString("file_id", "").trim()
                    val fileName = doc.optString("file_name", "").trim()
                    if (fileId.isNotEmpty()) {
                        handleIncomingIcsDocument(token, chatId, fileId, fileName, nowMs)
                        processedAny = true
                    }
                    continue
                }

                val text = msg.optString("text", "").trim()
                if (text.isNotEmpty()) {
                    handleIncomingMessage(token, chatId, text, nowMs)
                    processedAny = true
                }
            }
        }

        if (maxUpdateId > store.config.telegramLastUpdateId) {
            store.updateConfig { cur ->
                if (maxUpdateId > cur.telegramLastUpdateId) {
                    cur.copy(telegramLastUpdateId = maxUpdateId)
                } else {
                    cur
                }
            }
        }
        store.updateSnapshot { it.copy(telegramStatus = "BOT ACTIVO") }
        if (processedAny) {
            onMessageProcessed()
        }
        return processedAny
    }

    internal fun handleIncomingMessage(
        token: String,
        chatId: String,
        rawText: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return

        val (cmd, rest) = splitCommand(trimmed)

        // Global commands available in any state
        when (cmd) {
            "/cancel" -> {
                sessions.remove(chatId)
                sendReply(token, chatId, "❌ Operación cancelada. Envía /new para empezar de nuevo.")
                return
            }
            "/start", "/help" -> {
                sessions.remove(chatId)
                sendReply(
                    token,
                    chatId,
                    "👋 Asistente de tablet listo.\n" +
                        "Comandos:\n" +
                        "• /new - Crear recordatorio o cita paso a paso\n" +
                        "• /record - Añadir recordatorio con fecha y urgencia\n" +
                        "• /agenda - Añadir cita al calendario y recibir .ics\n" +
                        "• Reenvía un archivo .ics - Importar cita directamente a la agenda\n" +
                        "• /done <n> - Marcar recordatorio #n como hecho\n" +
                        "• /clear - Limpiar recordatorios completados\n" +
                        "• /nota <texto> - Fijar nota en pantalla\n" +
                        "• /cancel - Cancelar flujo actual"
                )
                return
            }
            "/new" -> {
                sessions[chatId] = ChatSession(step = BotStep.AWAITING_NEW_CHOICE)
                sendReply(
                    token,
                    chatId,
                    "¿Qué deseas crear?\n" +
                        "1️⃣ /record - Recordatorio\n" +
                        "2️⃣ /agenda - Cita de calendario\n" +
                        "Responde 1, 2, /record o /agenda (/cancel para cancelar)."
                )
                return
            }
            "/record" -> {
                handleRecordCommand(token, chatId, rest, nowMs)
                return
            }
            "/agenda" -> {
                handleAgendaCommand(token, chatId, rest, nowMs)
                return
            }
        }

        // Legacy quick commands (/nota, /done, /clear, nota:, pin:, !urgent)
        val quickResult = tryApplyQuickRule(store, trimmed)
        if (quickResult != null) {
            sessions.remove(chatId)
            if (quickResult.isNotBlank()) {
                sendReply(token, chatId, "✅ $quickResult")
            }
            return
        }

        // Stateful guided conversation
        val session = sessions[chatId] ?: ChatSession(step = BotStep.IDLE)
        when (session.step) {
            BotStep.IDLE -> {
                // Never dump arbitrary chat messages into tablet tasks
                sendReply(
                    token,
                    chatId,
                    "🤖 Usa /new para elegir entre recordatorio o cita, /record para un recordatorio o /agenda para una cita (.ics)."
                )
            }
            BotStep.AWAITING_NEW_CHOICE -> {
                val choice = trimmed.lowercase(Locale.US)
                when {
                    choice in setOf("1", "record", "recordatorio", "tarea", "1️⃣") -> {
                        handleRecordCommand(token, chatId, "", nowMs)
                    }
                    choice in setOf("2", "agenda", "cita", "evento", "calendario", "2️⃣") -> {
                        handleAgendaCommand(token, chatId, "", nowMs)
                    }
                    else -> {
                        sendReply(
                            token,
                            chatId,
                            "Elige una opción:\n1️⃣ /record - Recordatorio\n2️⃣ /agenda - Cita de calendario\n(/cancel para salir)"
                        )
                    }
                }
            }
            BotStep.AWAITING_RECORD_TEXT -> {
                sessions[chatId] = session.copy(step = BotStep.AWAITING_RECORD_DATE, draftText = trimmed)
                sendReply(
                    token,
                    chatId,
                    "📅 ¿Para qué fecha es?\nEj: hoy, mañana, 27/11, 2026-11-27 (o escribe 'no' si no tiene fecha)"
                )
            }
            BotStep.AWAITING_RECORD_DATE -> {
                val parsedDate = parseReminderDate(trimmed, store.config.timezone, nowMs)
                sessions[chatId] = session.copy(step = BotStep.AWAITING_RECORD_URGENCY, draftDate = parsedDate)
                sendReply(
                    token,
                    chatId,
                    "🚦 ¿Qué urgencia tiene?\n🔴 alta\n🟡 media\n⚪ baja"
                )
            }
            BotStep.AWAITING_RECORD_URGENCY -> {
                val urgency = parseUrgencyStrict(trimmed)
                if (urgency == null) {
                    sendReply(token, chatId, "Indica urgencia: alta, media o baja (/cancel para cancelar).")
                    return
                }
                saveReminderAndConfirm(token, chatId, session.draftText, session.draftDate, urgency)
                sessions.remove(chatId)
            }
            BotStep.AWAITING_AGENDA_TAG -> {
                val tag = sanitizeTag(trimmed)
                if (tag.isEmpty()) {
                    sendReply(token, chatId, "Indica una etiqueta corta (ej: MEDICO, TRABAJO, PERSONAL):")
                    return
                }
                sessions[chatId] = session.copy(step = BotStep.AWAITING_AGENDA_TITLE, draftTag = tag)
                sendReply(token, chatId, "📌 ¿Cuál es el título de la cita?")
            }
            BotStep.AWAITING_AGENDA_TITLE -> {
                sessions[chatId] = session.copy(step = BotStep.AWAITING_AGENDA_DATE, draftTitle = trimmed)
                sendReply(
                    token,
                    chatId,
                    "📅 ¿Qué fecha? (YYYY-MM-DD, DD/MM, hoy o mañana)"
                )
            }
            BotStep.AWAITING_AGENDA_DATE -> {
                val dateIso = parseAgendaDateIso(trimmed, store.config.timezone, nowMs)
                if (dateIso == null) {
                    sendReply(token, chatId, "Fecha no válida. Usa YYYY-MM-DD o DD/MM (ej: 2026-11-28 o 28/11):")
                    return
                }
                sessions[chatId] = session.copy(step = BotStep.AWAITING_AGENDA_TIME, draftDate = dateIso)
                sendReply(token, chatId, "⏰ ¿A qué hora? (HH:MM, ej: 17:30)")
            }
            BotStep.AWAITING_AGENDA_TIME -> {
                val timeHm = parseTimeHm(trimmed)
                if (timeHm == null) {
                    sendReply(token, chatId, "Hora no válida. Usa formato HH:MM (ej: 09:30 o 18:00):")
                    return
                }
                saveAgendaAndSendIcs(
                    token = token,
                    chatId = chatId,
                    tag = session.draftTag,
                    title = session.draftTitle,
                    dateIso = session.draftDate,
                    startTime = timeHm,
                    nowMs = nowMs
                )
                sessions.remove(chatId)
            }
        }
    }

    private fun handleRecordCommand(token: String, chatId: String, rest: String, nowMs: Long) {
        if (rest.isBlank()) {
            sessions[chatId] = ChatSession(step = BotStep.AWAITING_RECORD_TEXT)
            sendReply(
                token,
                chatId,
                "📝 Escribe el texto del recordatorio.\n" +
                    "(También puedes usar formato directo: /record Texto | fecha | alta/media/baja)"
            )
            return
        }

        if (rest.contains("|")) {
            val parts = rest.split("|").map { it.trim() }
            val text = parts.getOrNull(0).orEmpty()
            if (text.isEmpty()) {
                sessions[chatId] = ChatSession(step = BotStep.AWAITING_RECORD_TEXT)
                sendReply(token, chatId, "📝 Escribe el texto del recordatorio:")
                return
            }
            val rawDate = parts.getOrNull(1).orEmpty()
            val rawUrgency = parts.getOrNull(2).orEmpty()
            val dueDate = if (rawDate.isNotEmpty()) parseReminderDate(rawDate, store.config.timezone, nowMs) else ""
            val urgency = parseUrgencyStrict(rawUrgency) ?: QuickTask.URGENCY_LOW
            saveReminderAndConfirm(token, chatId, text, dueDate, urgency)
            sessions.remove(chatId)
        } else {
            sessions[chatId] = ChatSession(step = BotStep.AWAITING_RECORD_DATE, draftText = rest)
            sendReply(
                token,
                chatId,
                "📅 ¿Para qué fecha es «$rest»?\nEj: hoy, mañana, 27/11, 2026-11-27 (o 'no' sin fecha)"
            )
        }
    }

    private fun handleAgendaCommand(token: String, chatId: String, rest: String, nowMs: Long) {
        if (rest.isBlank()) {
            sessions[chatId] = ChatSession(step = BotStep.AWAITING_AGENDA_TAG)
            sendReply(
                token,
                chatId,
                "🏷️ ¿Qué etiqueta quieres para la cita? (una palabra, ej: MEDICO, TRABAJO, PERSONAL)\n" +
                    "(Formato directo: /agenda ETIQUETA Título | YYYY-MM-DD | HH:MM)"
            )
            return
        }

        val parts = rest.split("|").map { it.trim() }
        if (parts.size < 3) {
            sendReply(
                token,
                chatId,
                "Formato: /agenda <ETIQUETA> <TITULO> | <YYYY-MM-DD> | <HH:MM>\n" +
                    "O envía solo /agenda para que te guíe paso a paso."
            )
            return
        }

        val headTokens = parts[0].split(Regex("\\s+"), limit = 2)
        if (headTokens.size < 2 || headTokens[1].isBlank()) {
            sendReply(
                token,
                chatId,
                "Indica etiqueta y título antes de '|'. Ej:\n/agenda MEDICO Dentista | 2026-11-28 | 17:30"
            )
            return
        }
        val tag = sanitizeTag(headTokens[0])
        val title = headTokens[1].trim()
        val dateIso = parseAgendaDateIso(parts[1], store.config.timezone, nowMs)
        val startTime = parseTimeHm(parts[2])
        if (tag.isEmpty() || dateIso == null || startTime == null) {
            sendReply(
                token,
                chatId,
                "Revisa etiqueta, fecha (YYYY-MM-DD o DD/MM) y hora (HH:MM).\n" +
                    "Ej: /agenda MEDICO Dentista | 2026-11-28 | 17:30"
            )
            return
        }

        saveAgendaAndSendIcs(
            token = token,
            chatId = chatId,
            tag = tag,
            title = title,
            dateIso = dateIso,
            startTime = startTime,
            nowMs = nowMs
        )
        sessions.remove(chatId)
    }

    private fun saveReminderAndConfirm(
        token: String,
        chatId: String,
        text: String,
        dueDate: String,
        urgency: String
    ) {
        val normUrgency = QuickTask.normalizeUrgency(urgency)
        addTask(
            store = store,
            text = text,
            priority = normUrgency == QuickTask.URGENCY_HIGH,
            dueDate = dueDate,
            urgency = normUrgency
        )
        val urgencyLabel = when (normUrgency) {
            QuickTask.URGENCY_HIGH -> "ALTA"
            QuickTask.URGENCY_MED -> "MEDIA"
            else -> "BAJA"
        }
        val meta = buildString {
            if (dueDate.isNotBlank()) append(" · 📅 $dueDate")
            append(" · 🚦 $urgencyLabel")
        }
        sendReply(token, chatId, "✅ Recordatorio guardado en la tablet: $text$meta")
    }

    private fun saveAgendaAndSendIcs(
        token: String,
        chatId: String,
        tag: String,
        title: String,
        dateIso: String,
        startTime: String,
        nowMs: Long
    ) {
        val formattedTitle = "[$tag] $title"
        val eventId = "tg_ev_${nowMs}_${idSeq.incrementAndGet()}"
        val manualEvent = ManualCalendarEvent(
            id = eventId,
            title = formattedTitle,
            dateIso = dateIso,
            startTime = startTime
        )
        store.updateConfig { cur ->
            cur.copy(manualEvents = cur.manualEvents + manualEvent)
        }

        // Reflect immediately on the tablet's snapshot calendar without waiting for full sync
        val tz = TimeZone.getTimeZone(store.config.timezone)
        val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }.format(Date(nowMs))
        val epoch = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = tz }
                .parse("$dateIso $startTime")?.time ?: nowMs
        } catch (_: Exception) {
            nowMs
        }
        if (dateIso >= todayIso) {
            val calItem = CalendarEventItem(
                id = eventId,
                title = formattedTitle,
                dateIso = dateIso,
                startTime = startTime,
                endTime = "",
                location = "",
                isToday = dateIso == todayIso,
                startEpochMs = epoch
            )
            store.updateSnapshot { snap ->
                val merged = (snap.calendarEvents.filterNot { it.id == eventId } + calItem)
                    .sortedWith(compareBy({ it.dateIso }, { it.startTime }, { it.startEpochMs }))
                    .take(12)
                snap.copy(calendarEvents = merged)
            }
        }
        onCalendarUpdated()

        val icsContent = buildIcsContent(
            uid = eventId,
            summary = formattedTitle,
            dateIso = dateIso,
            startTime = startTime,
            timezoneId = store.config.timezone,
            nowMs = nowMs
        )
        val safeSlug = title.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(24)
            .ifBlank { "cita" }
        val filename = "${dateIso}_${safeSlug}.ics"
        val caption = "📅 Cita guardada en la tablet y adjunto tu archivo .ics para el móvil.\n$formattedTitle · $dateIso $startTime"
        val sent = transport.sendDocument(
            token = token,
            chatId = chatId,
            filename = filename,
            bytes = icsContent.toByteArray(Charsets.UTF_8),
            caption = caption
        )
        if (!sent) {
            sendReply(
                token,
                chatId,
                "📅 Cita guardada en la tablet: $formattedTitle ($dateIso $startTime). (No se pudo enviar el adjunto .ics)"
            )
        }
    }

    internal fun handleIncomingIcsDocument(
        token: String,
        chatId: String,
        fileId: String,
        fileName: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val icsContent = transport.downloadFileText(token, fileId)
        if (icsContent.isNullOrBlank()) {
            val label = if (fileName.isNotBlank()) " ($fileName)" else ""
            sendReply(token, chatId, "⚠️ No se pudo descargar el archivo .ics$label.")
            return
        }

        val parsedEvents = CalendarIcsParser.parseIcsEvents(
            icsContent = icsContent,
            timezoneId = store.config.timezone,
            nowMs = nowMs
        )
        if (parsedEvents.isEmpty()) {
            sendReply(token, chatId, "⚠️ El archivo .ics no contiene eventos válidos para añadir a la agenda.")
            return
        }

        val imported = parsedEvents.map { ev ->
            val eventId = "tg_ics_${nowMs}_${idSeq.incrementAndGet()}"
            val manual = ManualCalendarEvent(
                id = eventId,
                title = ev.title,
                dateIso = ev.dateIso,
                startTime = ev.startTime,
                endTime = ev.endTime,
                location = ev.location
            )
            manual to ev.copy(id = eventId)
        }

        store.updateConfig { cur ->
            cur.copy(manualEvents = cur.manualEvents + imported.map { it.first })
        }

        val tz = TimeZone.getTimeZone(store.config.timezone)
        val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }.format(Date(nowMs))
        val snapshotItems = imported.map { it.second }.filter { it.dateIso >= todayIso }
        if (snapshotItems.isNotEmpty()) {
            val newIds = snapshotItems.map { it.id }.toSet()
            store.updateSnapshot { snap ->
                val merged = (snap.calendarEvents.filterNot { it.id in newIds } + snapshotItems)
                    .sortedWith(compareBy({ it.dateIso }, { it.startTime }, { it.startEpochMs }))
                    .take(12)
                snap.copy(calendarEvents = merged)
            }
        }
        onCalendarUpdated()

        if (imported.size == 1) {
            val ev = imported.first().first
            val timePart = if (ev.endTime.isNotBlank()) "${ev.startTime}-${ev.endTime}" else ev.startTime
            val locPart = if (ev.location.isNotBlank()) " · 📍 ${ev.location}" else ""
            sendReply(token, chatId, "📅 Añadido a la agenda: ${ev.title} (${ev.dateIso} $timePart$locPart)")
        } else {
            val lines = imported.joinToString("\n") { (ev, _) ->
                val timePart = if (ev.endTime.isNotBlank()) "${ev.startTime}-${ev.endTime}" else ev.startTime
                val locPart = if (ev.location.isNotBlank()) " · 📍 ${ev.location}" else ""
                "• ${ev.title} (${ev.dateIso} $timePart$locPart)"
            }
            sendReply(token, chatId, "📅 Añadidos ${imported.size} eventos a la agenda:\n$lines")
        }
    }

    private fun sendReply(token: String, chatId: String, text: String) {
        if (token.isNotBlank() && chatId.isNotBlank()) {
            transport.sendMessage(token, chatId, text)
        }
    }

    private fun parseAllowedChatIds(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return raw.split(",", ";", " ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun sleepQuietly(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            running
        } catch (_: InterruptedException) {
            false
        }
    }

    companion object {
        private const val LONG_POLL_TIMEOUT_SEC = 20
        private const val HTTP_TIMEOUT_MS = 30_000
        private const val IDLE_POLL_SLEEP_MS = 3_000L
        private const val ERROR_BACKOFF_MS = 5_000L

        private val idSeq = AtomicLong(0L)
        private val DONE_REGEX = Regex("^/done(?:@\\S+)?\\s+(\\d+)\\s*$", RegexOption.IGNORE_CASE)
        private val CLEAR_REGEX = Regex("^/clear(?:@\\S+)?(?:\\s+.*)?$", RegexOption.IGNORE_CASE)
        private val NOTE_PREFIXES = listOf("nota:", "/nota ", "pin:")
        private val ISO_DATE_REGEX = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$")
        private val DMY_DATE_REGEX = Regex("^(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?$")
        private val TIME_REGEX = Regex("^(\\d{1,2})(?:[:.](\\d{2}))?$")

        private interface IncomingRule {
            fun tryApply(store: LocalStore, trimmed: String): String?
        }

        private val RULES: List<IncomingRule> = listOf(
            object : IncomingRule {
                override fun tryApply(store: LocalStore, trimmed: String): String? {
                    val lower = trimmed.lowercase(Locale.US)
                    val matchedPrefix = NOTE_PREFIXES.firstOrNull { lower.startsWith(it) }
                    val noteBody = when {
                        matchedPrefix != null -> trimmed.substring(matchedPrefix.length).trim()
                        lower == "/nota" -> ""
                        else -> return null
                    }
                    store.updateConfig { cur -> cur.copy(pinnedNote = noteBody) }
                    return if (noteBody.isEmpty()) "NOTA BORRADA" else "NOTA ACTUALIZADA"
                }
            },
            object : IncomingRule {
                override fun tryApply(store: LocalStore, trimmed: String): String? {
                    if (!CLEAR_REGEX.matches(trimmed)) return null
                    var removedCount = 0
                    store.updateConfig { cur ->
                        val remaining = cur.tasks.filterNot { it.done }
                        removedCount = cur.tasks.size - remaining.size
                        cur.copy(tasks = remaining)
                    }
                    return "ELIMINADAS $removedCount TAREAS"
                }
            },
            object : IncomingRule {
                override fun tryApply(store: LocalStore, trimmed: String): String? {
                    val match = DONE_REGEX.matchEntire(trimmed) ?: return null
                    val oneBased = match.groupValues[1].toIntOrNull() ?: return "INDICE INVALIDO"
                    val idx = oneBased - 1
                    var marked = false
                    store.updateConfig { cur ->
                        if (idx !in cur.tasks.indices) {
                            cur
                        } else {
                            marked = true
                            cur.copy(
                                tasks = cur.tasks.mapIndexed { i, task ->
                                    if (i == idx) task.copy(done = true) else task
                                }
                            )
                        }
                    }
                    return if (marked) "TAREA #$oneBased COMPLETADA" else "TAREA #$oneBased NO EXISTE"
                }
            },
            object : IncomingRule {
                override fun tryApply(store: LocalStore, trimmed: String): String? {
                    if (!trimmed.startsWith("!")) return null
                    val taskText = trimmed.substring(1).trim()
                    if (taskText.isEmpty()) return ""
                    addTask(store, taskText, priority = true, urgency = QuickTask.URGENCY_HIGH)
                    return "TAREA PRIORITARIA AÑADIDA"
                }
            }
        )

        private fun tryApplyQuickRule(store: LocalStore, trimmed: String): String? {
            for (rule in RULES) {
                val result = rule.tryApply(store, trimmed)
                if (result != null) return result
            }
            return null
        }

        fun applyIncomingText(store: LocalStore, rawText: String): String {
            val trimmed = rawText.trim()
            if (trimmed.isEmpty()) return ""

            tryApplyQuickRule(store, trimmed)?.let { return it }

            addTask(store, trimmed, priority = false, urgency = QuickTask.URGENCY_LOW)
            return "TAREA AÑADIDA"
        }

        private fun addTask(
            store: LocalStore,
            text: String,
            priority: Boolean,
            dueDate: String = "",
            urgency: String = if (priority) QuickTask.URGENCY_HIGH else QuickTask.URGENCY_LOW
        ) {
            val normUrgency = QuickTask.normalizeUrgency(urgency, priority)
            val task = QuickTask(
                id = "tg_${System.currentTimeMillis()}_${idSeq.incrementAndGet()}",
                text = text,
                done = false,
                priority = priority || normUrgency == QuickTask.URGENCY_HIGH,
                dueDate = dueDate.trim(),
                urgency = normUrgency
            )
            store.updateConfig { cur -> cur.copy(tasks = listOf(task) + cur.tasks) }
        }

        internal fun isIcsDocument(doc: JSONObject): Boolean {
            val fileName = doc.optString("file_name", "").trim().lowercase(Locale.US)
            val mimeType = doc.optString("mime_type", "").trim().lowercase(Locale.US)
            return fileName.endsWith(".ics") || mimeType == "text/calendar" || mimeType.startsWith("text/calendar;")
        }

        internal fun splitCommand(trimmed: String): Pair<String, String> {
            if (!trimmed.startsWith("/")) return "" to trimmed
            val firstSpace = trimmed.indexOfFirst { it.isWhitespace() }
            val rawCmd = if (firstSpace < 0) trimmed else trimmed.substring(0, firstSpace)
            val rest = if (firstSpace < 0) "" else trimmed.substring(firstSpace + 1).trim()
            val normalizedCmd = rawCmd.substringBefore('@').lowercase(Locale.US)
            return normalizedCmd to rest
        }

        internal fun sanitizeTag(raw: String): String {
            val cleaned = raw.trim()
                .removePrefix("[")
                .removeSuffix("]")
                .trim()
                .split(Regex("\\s+"))
                .firstOrNull()
                .orEmpty()
                .replace(Regex("[\\[\\]]"), "")
            return cleaned.uppercase(Locale.US)
        }

        internal fun parseUrgencyStrict(raw: String): String? {
            val normalized = raw.trim().lowercase(Locale.US)
                .replace("🔴", "")
                .replace("🟡", "")
                .replace("⚪", "")
                .trim()
            return when (normalized) {
                "alta", "high", "urgente", "rojo", "red", "3", "!" -> QuickTask.URGENCY_HIGH
                "media", "med", "medium", "normal", "ambar", "ámbar", "amber", "2" -> QuickTask.URGENCY_MED
                "baja", "low", "1" -> QuickTask.URGENCY_LOW
                else -> null
            }
        }

        internal fun parseReminderDate(raw: String, timezoneId: String, nowMs: Long = System.currentTimeMillis()): String {
            val clean = raw.trim()
            val lower = clean.lowercase(Locale.US)
            if (lower in setOf("", "no", "ninguna", "sin", "sin fecha", "-", "skip", "none")) {
                return ""
            }
            val tz = TimeZone.getTimeZone(timezoneId)
            val shortFmt = SimpleDateFormat("dd/MM", Locale.US).apply { timeZone = tz }
            return when (lower) {
                "hoy", "today" -> shortFmt.format(Date(nowMs))
                "mañana", "manana", "tomorrow" -> {
                    val cal = Calendar.getInstance(tz).apply {
                        timeInMillis = nowMs
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                    shortFmt.format(cal.time)
                }
                else -> clean
            }
        }

        internal fun parseAgendaDateIso(raw: String, timezoneId: String, nowMs: Long = System.currentTimeMillis()): String? {
            val clean = raw.trim()
            val lower = clean.lowercase(Locale.US)
            val tz = TimeZone.getTimeZone(timezoneId)
            val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }

            if (lower == "hoy" || lower == "today") {
                return isoFmt.format(Date(nowMs))
            }
            if (lower == "mañana" || lower == "manana" || lower == "tomorrow") {
                val cal = Calendar.getInstance(tz).apply {
                    timeInMillis = nowMs
                    add(Calendar.DAY_OF_YEAR, 1)
                }
                return isoFmt.format(cal.time)
            }

            ISO_DATE_REGEX.matchEntire(clean)?.let { m ->
                val y = m.groupValues[1].toIntOrNull() ?: return null
                val mo = m.groupValues[2].toIntOrNull() ?: return null
                val d = m.groupValues[3].toIntOrNull() ?: return null
                if (mo !in 1..12 || d !in 1..31) return null
                return String.format(Locale.US, "%04d-%02d-%02d", y, mo, d)
            }

            DMY_DATE_REGEX.matchEntire(clean)?.let { m ->
                val d = m.groupValues[1].toIntOrNull() ?: return null
                val mo = m.groupValues[2].toIntOrNull() ?: return null
                val rawY = m.groupValues[3]
                val y = when {
                    rawY.isEmpty() -> Calendar.getInstance(tz).apply { timeInMillis = nowMs }.get(Calendar.YEAR)
                    rawY.length == 2 -> 2000 + (rawY.toIntOrNull() ?: return null)
                    else -> rawY.toIntOrNull() ?: return null
                }
                if (mo !in 1..12 || d !in 1..31) return null
                return String.format(Locale.US, "%04d-%02d-%02d", y, mo, d)
            }

            return null
        }

        internal fun parseTimeHm(raw: String): String? {
            val clean = raw.trim()
            val m = TIME_REGEX.matchEntire(clean) ?: return null
            val h = m.groupValues[1].toIntOrNull() ?: return null
            val min = if (m.groupValues[2].isEmpty()) 0 else (m.groupValues[2].toIntOrNull() ?: return null)
            if (h !in 0..23 || min !in 0..59) return null
            return String.format(Locale.US, "%02d:%02d", h, min)
        }

        /**
         * Builds an RFC 5545 .ics calendar file for a single VEVENT.
         * By design, does NOT emit any VALARM block (no default pre-alert).
         */
        fun buildIcsContent(
            uid: String,
            summary: String,
            dateIso: String,
            startTime: String,
            timezoneId: String = "Europe/Madrid",
            durationMinutes: Int = 60,
            nowMs: Long = System.currentTimeMillis()
        ): String {
            val tz = TimeZone.getTimeZone(timezoneId)
            val parseFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = tz }
            val startDate = parseFmt.parse("$dateIso $startTime") ?: Date(nowMs)
            val endCal = Calendar.getInstance(tz).apply {
                time = startDate
                add(Calendar.MINUTE, durationMinutes.coerceAtLeast(15))
            }
            val icsLocalFmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = tz }
            val icsUtcFmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            val dtStamp = icsUtcFmt.format(Date(nowMs))
            val dtStart = icsLocalFmt.format(startDate)
            val dtEnd = icsLocalFmt.format(endCal.time)
            val escapedSummary = escapeIcsText(summary)

            return buildString {
                append("BEGIN:VCALENDAR\r\n")
                append("VERSION:2.0\r\n")
                append("PRODID:-//ASSISTANT//SM-T535//ES\r\n")
                append("CALSCALE:GREGORIAN\r\n")
                append("METHOD:PUBLISH\r\n")
                append("BEGIN:VEVENT\r\n")
                append("UID:$uid@assistant.local\r\n")
                append("DTSTAMP:$dtStamp\r\n")
                append("DTSTART:$dtStart\r\n")
                append("DTEND:$dtEnd\r\n")
                append("SUMMARY:$escapedSummary\r\n")
                append("END:VEVENT\r\n")
                append("END:VCALENDAR\r\n")
            }
        }

        private fun escapeIcsText(value: String): String =
            value.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
    }
}
