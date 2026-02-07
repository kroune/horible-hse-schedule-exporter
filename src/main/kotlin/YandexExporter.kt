import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

class YandexCalendarExporter(private val email: String, private val appPassword: String) {
    private val client = HttpClient(CIO) {
        install(io.ktor.client.plugins.auth.Auth) {
            basic {
                credentials {
                    BasicAuthCredentials(username = email, password = appPassword)
                }
                sendWithoutRequest { request ->
                    request.url.host == "caldav.yandex.ru"
                }
            }
        }
    }

    suspend fun exportLessonToCalendar(lesson: Lesson): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val icalContent = convertLessonToICal(lesson)
                val url = "https://caldav.yandex.ru/calendars/$email/events-default/${lesson.hash}.ics"

                val response = client.put(url) {
                    header("Content-Type", "text/calendar; charset=utf-8")
                    setBody(icalContent)
                }

                response.status.isSuccess().also {
                    if (!it) {
                        response.request.url.also(::println)
                        response.status.value.also(::println)
                        response.bodyAsText().also(::println)
                    }
                }
            } catch (e: Exception) {
                println("Ошибка при экспорте в Яндекс Календарь: ${e.message}")
                false
            }
        }
    }

    private fun convertLessonToICal(lesson: Lesson): String {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Timetable Exporter//EN
            BEGIN:VEVENT
            UID:${lesson.hash}
            SUMMARY:${lesson.discipline}
            DESCRIPTION:${buildDescription(lesson)}
            LOCATION:${lesson.building}, ${lesson.auditorium}
            DTSTART:${formatDateForICal(lesson.dateStart)}
            DTEND:${formatDateForICal(lesson.dateEnd)}
            RRULE:FREQ=DAILY;COUNT=1
            CATEGORIES:${lesson.type}
            PRIORITY:${lesson.importanceLevel}
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }

    private fun formatDateForICal(dateTime: String): String {
        return dateTime.replace("-", "")
            .replace(":", "")
            .replace("T", "")
            .replace("Z", "Z")
    }

    private fun buildDescription(lesson: Lesson): String {
        return """
            Дисциплина: ${lesson.discipline}
            Тип: ${lesson.type}
            Аудитория: ${lesson.auditorium}
            Здание: ${lesson.building}
            Преподаватели: ${lesson.lecturerProfiles.joinToString { it.fullName }}
            Ссылка: ${lesson.disciplineLink}
        """.trimIndent().replace("\n", "\\n")
    }

    suspend fun close() {
        client.close()
    }
}