package exporter

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import config.GoogleCalendarConfig
import config.RetryConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import model.Lesson
import org.slf4j.LoggerFactory
import java.io.FileInputStream

/**
 * Exports lessons to Google Calendar using Service Account credentials.
 *
 * Note: Service accounts have their own internal calendar when using "primary".
 * To write to a user's calendar, the user must share their calendar with the
 * service account email (client_email from the JSON credentials file).
 *
 * @param config Google Calendar configuration containing credentials file path
 * @param retryConfig Retry configuration for handling transient failures
 */
class GoogleCalendarExporter(
    private val config: GoogleCalendarConfig,
    private val retryConfig: RetryConfig
) : CalendarExporter {

    private val logger = LoggerFactory.getLogger(GoogleCalendarExporter::class.java)

    companion object {
        private const val APPLICATION_NAME = "HSE Calendar Exporter"
        private const val MAX_RESULTS_PER_PAGE = 250
        private const val HASH_PREFIX = "hse-calendar-hash:"
        private val JSON_FACTORY = GsonFactory.getDefaultInstance()
        private val SCOPES = listOf(CalendarScopes.CALENDAR_EVENTS, CalendarScopes.CALENDAR)
    }

    /**
     * Target calendar ID. Use "primary" for the service account's own calendar,
     * or specify a user's email if the calendar is shared with the service account.
     */
    private val targetCalendarId = "primary"

    private val httpTransport: NetHttpTransport by lazy {
        GoogleNetHttpTransport.newTrustedTransport()
    }

    private val calendarService: Calendar by lazy {
        createCalendarService()
    }

    private fun createCalendarService(): Calendar {
        val credentials = FileInputStream(config.credentialsFile).use { inputStream ->
            GoogleCredentials.fromStream(inputStream).createScoped(SCOPES)
        }
        val requestInitializer = HttpCredentialsAdapter(credentials)

        return Calendar.Builder(httpTransport, JSON_FACTORY, requestInitializer)
            .setApplicationName(APPLICATION_NAME)
            .build()
    }

    override suspend fun export(lessons: List<Lesson>): Map<String, ExportResult> {
        logger.info("Exporting ${lessons.size} lessons to Google Calendar")
        return lessons.associate { lesson ->
            lesson.hash to exportSingle(lesson)
        }
    }

    override suspend fun exportSingle(lesson: Lesson): ExportResult {
        return withRetry {
            withContext(Dispatchers.IO) {
                try {
                    val existingEventId = findEventByLessonHash(lesson.hash)
                    val event = createEventFromLesson(lesson)

                    val resultEvent = if (existingEventId != null) {
                        logger.debug("Updating existing event {} for lesson {}", existingEventId, lesson.discipline)
                        calendarService.events()
                            .update(targetCalendarId, existingEventId, event)
                            .execute()
                    } else {
                        logger.debug("Creating new event for lesson {}", lesson.discipline)
                        calendarService.events()
                            .insert(targetCalendarId, event)
                            .execute()
                    }

                    ExportResult.Success(resultEvent.id)
                } catch (e: Exception) {
                    logger.error("Error exporting lesson '{}' to Google Calendar: {}", lesson.discipline, e.message)
                    ExportResult.Error("Error exporting to Google Calendar: ${e.message}", e)
                }
            }
        }
    }

    override suspend fun delete(eventIds: List<String>): Map<String, Boolean> {
        logger.info("Deleting ${eventIds.size} events from Google Calendar")
        return eventIds.associateWith { eventId ->
            withRetry {
                withContext(Dispatchers.IO) {
                    try {
                        calendarService.events().delete(targetCalendarId, eventId).execute()
                        logger.debug("Successfully deleted event {}", eventId)
                        true
                    } catch (e: Exception) {
                        logger.error("Error deleting event {}: {}", eventId, e.message)
                        false
                    }
                }
            }
        }
    }

    override suspend fun getExistingEvents(): List<CalendarEvent> {
        return withContext(Dispatchers.IO) {
            try {
                val events = mutableListOf<CalendarEvent>()
                var pageToken: String? = null

                do {
                    val request = calendarService.events()
                        .list(targetCalendarId)
                        .setMaxResults(MAX_RESULTS_PER_PAGE)
                        .setPageToken(pageToken)
                        .setQ(APPLICATION_NAME)

                    val response = request.execute()

                    response.items?.forEach { event ->
                        if (event.description?.contains(HASH_PREFIX) == true) {
                            events.add(convertGoogleEventToCalendarEvent(event))
                        }
                    }

                    pageToken = response.nextPageToken
                } while (pageToken != null)

                logger.debug("Found {} existing HSE Calendar events", events.size)
                events
            } catch (e: Exception) {
                logger.error("Error fetching events from Google Calendar: {}", e.message)
                emptyList()
            }
        }
    }

    override suspend fun findEventByLessonHash(lessonHash: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val events = calendarService.events()
                    .list(targetCalendarId)
                    .setQ("$HASH_PREFIX$lessonHash")
                    .setMaxResults(1)
                    .execute()

                events.items?.firstOrNull()?.id
            } catch (e: Exception) {
                logger.warn("Error searching for event by hash {}: {}", lessonHash, e.message)
                null
            }
        }
    }

    override suspend fun close() {
        // Google Calendar client doesn't require explicit cleanup
        logger.debug("GoogleCalendarExporter closed")
    }

    /**
     * Executes an operation with exponential backoff retry logic.
     *
     * @param operation The suspend function to execute with retries
     * @return The result of the operation
     * @throws Exception The last exception thrown if all retries fail
     */
    private suspend fun <T> withRetry(operation: suspend () -> T): T {
        var lastException: Exception? = null
        var delayMs = retryConfig.initialDelaySeconds * 1000L
        val maxDelayMs = 60_000L // Cap delay at 1 minute

        repeat(retryConfig.maxAttempts) { attempt ->
            try {
                return operation()
            } catch (e: Exception) {
                lastException = e
                if (attempt < retryConfig.maxAttempts - 1) {
                    logger.warn("Attempt ${attempt + 1} failed, retrying in ${delayMs}ms: ${e.message}")
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
                }
            }
        }
        throw lastException ?: RuntimeException("Unknown error after ${retryConfig.maxAttempts} attempts")
    }

    /**
     * Creates a Google Calendar Event from a Lesson.
     *
     * Note: Attendees are not set because service accounts cannot invite
     * attendees without Domain-Wide Delegation of Authority.
     * Lecturer information is included in the event description instead.
     */
    private fun createEventFromLesson(lesson: Lesson): Event {
        val startEventDateTime = EventDateTime()
            .setDateTime(com.google.api.client.util.DateTime(lesson.dateStart))

        val endEventDateTime = EventDateTime()
            .setDateTime(com.google.api.client.util.DateTime(lesson.dateEnd))

        return Event()
            .setSummary(lesson.discipline)
            .setLocation("${lesson.building}, ${lesson.auditorium}")
            .setDescription(buildDescription(lesson))
            .setStart(startEventDateTime)
            .setEnd(endEventDateTime)
    }

    /**
     * Builds the event description with lesson details and a unique hash identifier.
     */
    private fun buildDescription(lesson: Lesson): String {
        val lecturersInfo = lesson.lecturerProfiles.joinToString("\n") { lecturer ->
            "• ${lecturer.fullName}${lecturer.email?.let { " ($it)" } ?: ""}"
        }

        return buildString {
            appendLine("Type: ${lesson.type}")
            appendLine("Auditorium: ${lesson.auditorium}")
            appendLine("Building: ${lesson.building}")
            if (lecturersInfo.isNotEmpty()) {
                appendLine("Lecturers:")
                appendLine(lecturersInfo)
            }
            lesson.disciplineLink?.let { appendLine("Link: $it") }
            appendLine()
            appendLine("---")
            append("$HASH_PREFIX${lesson.hash}")
        }
    }

    /**
     * Converts a Google Calendar Event to our internal CalendarEvent model.
     */
    private fun convertGoogleEventToCalendarEvent(event: Event): CalendarEvent {
        val lessonHash = event.description?.let { desc ->
            val regex = "${Regex.escape(HASH_PREFIX)}([a-zA-Z0-9]+)".toRegex()
            regex.find(desc)?.groupValues?.getOrNull(1)
        }

        return CalendarEvent(
            id = event.id,
            summary = event.summary ?: "",
            description = event.description,
            location = event.location,
            dateStart = event.start?.dateTime?.toString() ?: event.start?.date?.toString() ?: "",
            dateEnd = event.end?.dateTime?.toString() ?: event.end?.date?.toString() ?: "",
            lessonHash = lessonHash
        )
    }
}