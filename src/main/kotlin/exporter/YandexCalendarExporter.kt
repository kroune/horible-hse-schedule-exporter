package exporter

import config.RetryConfig
import config.YandexCalendarConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import model.Lesson
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Экспортер для Яндекс Календаря через CalDAV API
 */
class YandexCalendarExporter(
    private val config: YandexCalendarConfig,
    private val retryConfig: RetryConfig
) : CalendarExporter {

    private val client = HttpClient(CIO) {
        install(Auth) {
            basic {
                credentials {
                    BasicAuthCredentials(username = config.email, password = config.appPassword)
                }
                sendWithoutRequest { request ->
                    request.url.host == "caldav.yandex.ru"
                }
            }
        }
    }

    private val baseUrl = "https://caldav.yandex.ru/calendars/${config.email}/events-default"

    override suspend fun export(lessons: List<Lesson>): Map<String, ExportResult> {
        return lessons.associate { lesson ->
            lesson.hash to exportSingle(lesson)
        }
    }

    override suspend fun exportSingle(lesson: Lesson): ExportResult {
        return withRetry {
            withContext(Dispatchers.IO) {
                try {
                    val icalContent = convertLessonToICal(lesson)
                    val url = "$baseUrl/${lesson.hash}.ics"

                    val response = client.put(url) {
                        header("Content-Type", "text/calendar; charset=utf-8")
                        setBody(icalContent)
                    }

                    if (response.status.isSuccess()) {
                        ExportResult.Success(lesson.hash)
                    } else {
                        ExportResult.Error(
                            "HTTP ${response.status.value}: ${response.bodyAsText()}"
                        )
                    }
                } catch (e: Exception) {
                    ExportResult.Error("Ошибка при экспорте: ${e.message}", e)
                }
            }
        }
    }

    override suspend fun delete(eventIds: List<String>): Map<String, Boolean> {
        return eventIds.associateWith { eventId ->
            withRetry {
                withContext(Dispatchers.IO) {
                    try {
                        val url = "$baseUrl/$eventId.ics"
                        val response = client.delete(url)
                        response.status.isSuccess()
                    } catch (e: Exception) {
                        println("Ошибка при удалении события $eventId: ${e.message}")
                        false
                    }
                }
            }
        }
    }

    override suspend fun getExistingEvents(): List<CalendarEvent> {
        return withContext(Dispatchers.IO) {
            try {
                // CalDAV REPORT запрос для получения всех событий
                val reportBody = """
                    <?xml version="1.0" encoding="utf-8" ?>
                    <C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                        <D:prop>
                            <D:getetag/>
                            <C:calendar-data/>
                        </D:prop>
                        <C:filter>
                            <C:comp-filter name="VCALENDAR">
                                <C:comp-filter name="VEVENT"/>
                            </C:comp-filter>
                        </C:filter>
                    </C:calendar-query>
                """.trimIndent()

                val response = client.request(baseUrl) {
                    method = HttpMethod("REPORT")
                    header("Content-Type", "application/xml; charset=utf-8")
                    header("Depth", "1")
                    setBody(reportBody)
                }

                if (response.status.isSuccess()) {
                    parseCalDavResponse(response.bodyAsText())
                } else {
                    println("Ошибка при получении событий: ${response.status}")
                    emptyList()
                }
            } catch (e: Exception) {
                println("Ошибка при получении событий из Яндекс Календаря: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun findEventByLessonHash(lessonHash: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/$lessonHash.ics"
                val response = client.get(url)
                if (response.status.isSuccess()) lessonHash else null
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun close() {
        client.close()
    }

    /**
     * Выполняет операцию с retry и exponential backoff
     */
    private suspend fun <T> withRetry(operation: suspend () -> T): T {
        var lastException: Exception? = null
        var delay = retryConfig.initialDelaySeconds * 1000L

        repeat(retryConfig.maxAttempts) { attempt ->
            try {
                return operation()
            } catch (e: Exception) {
                lastException = e
                if (attempt < retryConfig.maxAttempts - 1) {
                    println("Попытка ${attempt + 1} не удалась, повтор через ${delay}мс: ${e.message}")
                    delay(delay)
                    delay *= 2 // Exponential backoff
                }
            }
        }

        throw lastException ?: RuntimeException("Неизвестная ошибка после ${retryConfig.maxAttempts} попыток")
    }

    private fun convertLessonToICal(lesson: Lesson): String {
        return """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//HSE Calendar Exporter//EN
BEGIN:VEVENT
UID:${lesson.hash}@hse-calendar
SUMMARY:${lesson.discipline}
DESCRIPTION:${buildDescription(lesson)}
LOCATION:${lesson.building}, ${lesson.auditorium}
DTSTART:${formatDateForICal(lesson.dateStart)}
DTEND:${formatDateForICal(lesson.dateEnd)}
CATEGORIES:${lesson.type}
PRIORITY:${lesson.importanceLevel}
END:VEVENT
END:VCALENDAR
        """.trimIndent()
    }

    private fun formatDateForICal(dateTime: String): String {
        // Формат входа: 2025-09-01T10:00:00Z или 2025-09-01T10:00:00+03:00
        return dateTime
            .replace("-", "")
            .replace(":", "")
            .replace("T", "T")
            .substringBefore("+")
            .substringBefore("Z") + "Z"
    }

    private fun buildDescription(lesson: Lesson): String {
        return buildString {
            append("Тип: ${lesson.type}\\n")
            append("Аудитория: ${lesson.auditorium}\\n")
            append("Здание: ${lesson.building}\\n")
            append("Преподаватели: ${lesson.lecturerProfiles.joinToString { it.fullName }}\\n")
            lesson.disciplineLink?.let { append("Ссылка: $it") }
        }
    }

    private fun parseCalDavResponse(xmlResponse: String): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()

        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val document: Document = builder.parse(xmlResponse.byteInputStream())

            val responseNodes = document.getElementsByTagNameNS("DAV:", "response")

            for (i in 0 until responseNodes.length) {
                val responseNode = responseNodes.item(i) as Element
                val hrefNodes = responseNode.getElementsByTagNameNS("DAV:", "href")
                val calendarDataNodes =
                    responseNode.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar-data")

                if (hrefNodes.length > 0 && calendarDataNodes.length > 0) {
                    val href = hrefNodes.item(0).textContent
                    val icalData = calendarDataNodes.item(0).textContent

                    parseICalEvent(href, icalData)?.let { events.add(it) }
                }
            }
        } catch (e: Exception) {
            println("Ошибка парсинга CalDAV ответа: ${e.message}")
        }

        return events
    }

    private fun parseICalEvent(href: String, icalData: String): CalendarEvent? {
        return try {
            val lines = icalData.lines()
            var uid = ""
            var summary = ""
            var description: String? = null
            var location: String? = null
            var dtstart = ""
            var dtend = ""

            for (line in lines) {
                when {
                    line.startsWith("UID:") -> uid = line.substringAfter("UID:")
                    line.startsWith("SUMMARY:") -> summary = line.substringAfter("SUMMARY:")
                    line.startsWith("DESCRIPTION:") -> description = line.substringAfter("DESCRIPTION:")
                    line.startsWith("LOCATION:") -> location = line.substringAfter("LOCATION:")
                    line.startsWith("DTSTART") -> dtstart = line.substringAfter(":")
                    line.startsWith("DTEND") -> dtend = line.substringAfter(":")
                }
            }

            // Извлекаем lessonHash из UID (формат: hash@hse-calendar)
            val lessonHash = uid.substringBefore("@hse-calendar").takeIf {
                uid.contains("@hse-calendar")
            }

            CalendarEvent(
                id = href.substringAfterLast("/").substringBefore(".ics"),
                summary = summary,
                description = description?.replace("\\n", "\n"),
                location = location,
                dateStart = dtstart,
                dateEnd = dtend,
                lessonHash = lessonHash
            )
        } catch (e: Exception) {
            null
        }
    }
}

