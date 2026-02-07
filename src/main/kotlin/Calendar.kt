import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventAttendee
import com.google.api.services.calendar.model.EventDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

object GoogleCalendarExporter {
    const val APPLICATION_NAME = "Timetable Exporter"
    val JSON_FACTORY = GsonFactory.getDefaultInstance()
    val SCOPES = listOf(CalendarScopes.CALENDAR_EVENTS)
    const val TOKENS_DIRECTORY_PATH = "tokens"
    const val CREDENTIALS_FILE_PATH = "credentials.json"

    private fun getCredentials(HTTP_TRANSPORT: NetHttpTransport): Credential {
        val inputStream = FileInputStream(CREDENTIALS_FILE_PATH)
        val clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, inputStream.reader())

        val flow = GoogleAuthorizationCodeFlow.Builder(
            HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES
        )
            .setDataStoreFactory(FileDataStoreFactory(File(TOKENS_DIRECTORY_PATH)))
            .setAccessType("offline")
            .build()

        val receiver = LocalServerReceiver.Builder().setPort(8888).build()
        return AuthorizationCodeInstalledApp(flow, receiver).authorize("user")
    }

    suspend fun exportLessonsToCalendar(lessons: List<Lesson>) = withContext(Dispatchers.IO) {
        try {
            val HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport()
            val service = GoogleCalendarService.createCalendarService()

            lessons.forEach { lesson ->
                val event = createEventFromLesson(lesson)
                service.events().insert("primary", event).execute()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException("Error exporting to Google Calendar", e)
        }
    }

    private fun createEventFromLesson(lesson: Lesson): Event {
        val startEventDateTime = EventDateTime()
            .setDateTime(
                com.google.api.client.util.DateTime(
                    lesson.dateStart
                )
            )

        val endEventDateTime = EventDateTime()
            .setDateTime(
                com.google.api.client.util.DateTime(
                    lesson.dateEnd
                )
            )

        val attendees = lesson.lecturerEmails.map { email ->
            EventAttendee().setEmail(email)
        }

        return Event()
            .setSummary(lesson.discipline)
            .setLocation("${lesson.building}, ${lesson.auditorium}")
            .setDescription(buildDescription(lesson))
            .setStart(startEventDateTime)
            .setEnd(endEventDateTime)
            .setAttendees(attendees)
    }

    private fun buildDescription(lesson: Lesson): String {
        return """
            Дисциплина: ${lesson.discipline}
            Тип: ${lesson.type}
            Аудитория: ${lesson.auditorium}
            Здание: ${lesson.building}
            Преподаватели: ${lesson.lecturerProfiles.joinToString { it.fullName }}
            Ссылка: ${lesson.disciplineLink}
        """.trimIndent()
    }
}