package com.javiblzq.assistant.providers

import android.content.ContextWrapper
import com.javiblzq.assistant.data.LocalStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Calendar
import java.util.TimeZone

class TelegramIcsImportTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun telegramImportsForwardedIcsDocumentIntoAgenda() {
        val store = LocalStore(object : ContextWrapper(null) {
            override fun getFilesDir() = folder.root
        })
        store.updateConfig { it.copy(telegramBotToken = "fake-token", telegramAllowedChatId = "100") }

        val sentMessages = mutableListOf<Pair<String, String>>()
        val filesById = mapOf(
            "file-ics-1" to """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:ev-vuelo-1
                DTSTART;TZID=Europe/Madrid:20261215T093000
                DTEND;TZID=Europe/Madrid:20261215T111500
                SUMMARY:Vuelo Madrid - Barcelona
                LOCATION:Aeropuerto T4
                END:VEVENT
                END:VCALENDAR
            """.trimIndent(),
            "file-ics-mime" to """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:ev-fisio-2
                DTSTART:20261126T180000
                DTEND:20261126T190000
                SUMMARY:Fisioterapeuta
                LOCATION:Clínica Centro
                END:VEVENT
                END:VCALENDAR
            """.trimIndent(),
            "file-ics-invalid" to "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR"
        )

        var calendarUpdatedCount = 0
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
            ): Boolean = true

            override fun downloadFileText(token: String, fileId: String): String? = filesById[fileId]
        }

        val bot = TelegramBotService(
            store = store,
            onCalendarUpdated = { calendarUpdatedCount++ },
            transport = fakeTransport
        )
        val fixedNow = Calendar.getInstance(TimeZone.getTimeZone("Europe/Madrid")).apply {
            set(2026, Calendar.NOVEMBER, 26, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val updatesJson = """
            {
              "ok": true,
              "result": [
                {
                  "update_id": 10,
                  "message": {
                    "chat": { "id": 100 },
                    "document": {
                      "file_id": "file-pdf-ignored",
                      "file_name": "factura.pdf",
                      "mime_type": "application/pdf"
                    }
                  }
                },
                {
                  "update_id": 11,
                  "message": {
                    "chat": { "id": 100 },
                    "document": {
                      "file_id": "file-ics-1",
                      "file_name": "VUELO.ICS",
                      "mime_type": "application/octet-stream"
                    }
                  }
                },
                {
                  "update_id": 12,
                  "message": {
                    "chat": { "id": 100 },
                    "document": {
                      "file_id": "file-ics-mime",
                      "file_name": "invite.txt",
                      "mime_type": "text/calendar"
                    }
                  }
                },
                {
                  "update_id": 13,
                  "message": {
                    "chat": { "id": 100 },
                    "document": {
                      "file_id": "file-ics-invalid",
                      "file_name": "vacio.ics",
                      "mime_type": "text/calendar"
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        assertTrue(bot.processUpdatesJson(updatesJson, fixedNow))
        assertEquals(2, store.config.manualEvents.size)
        assertEquals(2, calendarUpdatedCount)

        val flight = store.config.manualEvents[0]
        assertEquals("Vuelo Madrid - Barcelona", flight.title)
        assertEquals("2026-12-15", flight.dateIso)
        assertEquals("09:30", flight.startTime)
        assertEquals("11:15", flight.endTime)
        assertEquals("Aeropuerto T4", flight.location)

        val physio = store.config.manualEvents[1]
        assertEquals("Fisioterapeuta", physio.title)
        assertEquals("2026-11-26", physio.dateIso)
        assertEquals("18:00", physio.startTime)
        assertEquals("19:00", physio.endTime)
        assertEquals("Clínica Centro", physio.location)

        // Both upcoming events are immediately reflected in the tablet snapshot
        assertTrue(store.snapshot.calendarEvents.any { it.title == "Vuelo Madrid - Barcelona" && it.dateIso == "2026-12-15" })
        assertTrue(store.snapshot.calendarEvents.any { it.title == "Fisioterapeuta" && it.isToday })

        // Confirmations + warning for empty .ics (PDF was ignored)
        assertEquals(3, sentMessages.size)
        assertTrue(sentMessages[0].second.contains("Añadido a la agenda: Vuelo Madrid - Barcelona"))
        assertTrue(sentMessages[1].second.contains("Añadido a la agenda: Fisioterapeuta"))
        assertTrue(sentMessages[2].second.contains("no contiene eventos válidos"))
    }
}
