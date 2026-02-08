package io.github.kroune

import config.AppConfig
import config.CalendarType
import config.GoogleCalendarConfig
import config.PollingConfig
import config.RetryConfig
import config.YandexCalendarConfig
import exporter.CalendarEvent
import exporter.CompositeCalendarExporter
import exporter.ExportResult
import exporter.ExporterFactory
import exporter.YandexCalendarExporter
import model.Lesson
import model.Location
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApplicationTest {

    @Test
    fun testCalendarType() {
        assertEquals(CalendarType.GOOGLE, CalendarType.valueOf("GOOGLE"))
        assertEquals(CalendarType.YANDEX, CalendarType.valueOf("YANDEX"))
        assertEquals(CalendarType.BOTH, CalendarType.valueOf("BOTH"))
    }

    @Test
    fun testAppConfigDataClass() {
        val config = AppConfig(
            hseEmail = "test@edu.hse.ru",
            calendarType = CalendarType.YANDEX,
            googleConfig = GoogleCalendarConfig(
                credentialsFile = "credentials.json",
                tokensDirectory = "tokens"
            ),
            yandexConfig = YandexCalendarConfig(
                email = "test@yandex.ru",
                appPassword = "password"
            ),
            pollingConfig = PollingConfig(
                intervalHours = 24,
                monthsAhead = 1
            ),
            retryConfig = RetryConfig(
                maxAttempts = 3,
                initialDelaySeconds = 1
            )
        )

        assertEquals("test@edu.hse.ru", config.hseEmail)
        assertEquals(CalendarType.YANDEX, config.calendarType)
        assertEquals(24, config.pollingConfig.intervalHours)
        assertEquals(3, config.retryConfig.maxAttempts)
    }

    @Test
    fun testLessonModel() {
        val lesson = createTestLesson()

        assertEquals("test-id", lesson.id)
        assertEquals("Математика", lesson.discipline)
        assertEquals("test-hash", lesson.hash)
    }

    @Test
    fun testCalendarEvent() {
        val event = CalendarEvent(
            id = "event-1",
            summary = "Математика",
            description = "Тестовое описание",
            location = "Корпус А, 101",
            dateStart = "2025-09-01T10:00:00Z",
            dateEnd = "2025-09-01T11:30:00Z",
            lessonHash = "lesson-hash-123"
        )

        assertEquals("event-1", event.id)
        assertEquals("Математика", event.summary)
        assertEquals("lesson-hash-123", event.lessonHash)
    }

    @Test
    fun testExportResult() {
        val success = ExportResult.Success("event-id-123")
        val error = ExportResult.Error("Ошибка подключения")

        assertTrue(success is ExportResult.Success)
        assertEquals("event-id-123", (success as ExportResult.Success).eventId)

        assertTrue(error is ExportResult.Error)
        assertEquals("Ошибка подключения", (error as ExportResult.Error).message)
    }

    @Test
    fun testExporterFactory() {
        val config = createTestConfig(CalendarType.YANDEX)
        val exporter = ExporterFactory.createExporter(config)

        assertNotNull(exporter)
        assertTrue(exporter is YandexCalendarExporter)
    }

    @Test
    fun testExporterFactoryWithBoth() {
        val config = createTestConfig(CalendarType.BOTH)
        val exporter = ExporterFactory.createExporter(config)

        assertNotNull(exporter)
        assertTrue(exporter is CompositeCalendarExporter)
    }

    // Helper functions

    private fun createTestConfig(type: CalendarType) = AppConfig(
        hseEmail = "test@edu.hse.ru",
        calendarType = type,
        googleConfig = GoogleCalendarConfig(
            credentialsFile = "credentials.json",
            tokensDirectory = "tokens"
        ),
        yandexConfig = YandexCalendarConfig(
            email = "test@yandex.ru",
            appPassword = "password"
        ),
        pollingConfig = PollingConfig(
            intervalHours = 24,
            monthsAhead = 1
        ),
        retryConfig = RetryConfig(
            maxAttempts = 3,
            initialDelaySeconds = 1
        )
    )

    private fun createTestLesson() = Lesson(
        id = "test-id",
        building = "Корпус А",
        type = "Лекция",
        stream = "Поток 1",
        auditoriumId = 101,
        auditorium = "101",
        city = "Москва",
        dateStart = "2025-09-01T10:00:00+03:00",
        dateEnd = "2025-09-01T11:30:00+03:00",
        createdAt = "2025-08-01T00:00:00Z",
        importanceLevel = 1,
        buildingId = 1,
        location = Location(type = "Point", coordinates = listOf(55.75, 37.62)),
        discipline = "Математика",
        lessonNumberStart = 1,
        lessonNumberEnd = 2,
        duration = listOf(90),
        kindOfWork = "Лекция",
        isBan = false,
        hash = "test-hash"
    )
}
