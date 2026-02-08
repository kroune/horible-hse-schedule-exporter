package io.github.kroune

import exporter.CalendarEvent
import exporter.CalendarExporter
import exporter.ExportResult
import kotlinx.coroutines.runBlocking
import model.Lesson
import model.Location
import org.junit.Test
import sync.TimetableSyncService
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Тесты для TimetableSyncService
 */
class SyncServiceTest {

    @Test
    fun testSyncWithEmptyCalendar() = runBlocking {
        val mockExporter = MockCalendarExporter()
        val syncService = TimetableSyncService(mockExporter)

        val lessons = listOf(createTestLesson("1"), createTestLesson("2"))
        val result = syncService.sync(lessons)

        // Все занятия должны быть добавлены
        assertEquals(2, result.added.size)
        assertEquals(0, result.updated.size)
        assertEquals(0, result.deleted.size)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun testSyncWithExistingEvents() = runBlocking {
        val existingEvents = listOf(
            CalendarEvent(
                id = "event-1",
                summary = "Математика",
                description = "",
                location = "Корпус А, 101",
                dateStart = "2025-09-01T10:00:00Z",
                dateEnd = "2025-09-01T11:30:00Z",
                lessonHash = "hash-1"
            )
        )

        val mockExporter = MockCalendarExporter(existingEvents)
        val syncService = TimetableSyncService(mockExporter)

        // Добавляем новое занятие, существующее оставляем
        val lessons = listOf(
            createTestLessonWithHash("hash-1", "Математика"),
            createTestLessonWithHash("hash-2", "Физика")
        )

        val result = syncService.sync(lessons)

        // Одно новое занятие добавлено, существующее не изменено
        assertEquals(1, result.added.size)
        assertEquals("hash-2", result.added[0])
    }

    @Test
    fun testSyncDeletesRemovedLessons() = runBlocking {
        val existingEvents = listOf(
            CalendarEvent(
                id = "event-1",
                summary = "Отменённый предмет",
                description = "",
                location = "Корпус А, 101",
                dateStart = "2025-09-01T10:00:00Z",
                dateEnd = "2025-09-01T11:30:00Z",
                lessonHash = "cancelled-hash"
            )
        )

        val mockExporter = MockCalendarExporter(existingEvents)
        val syncService = TimetableSyncService(mockExporter)

        // Пустой список занятий - всё должно удалиться
        val result = syncService.sync(emptyList())

        assertEquals(0, result.added.size)
        assertEquals(1, result.deleted.size)
    }

    // Helper functions

    private fun createTestLesson(suffix: String) = Lesson(
        id = "test-id-$suffix",
        building = "Корпус А",
        type = "Лекция",
        stream = "Поток 1",
        auditoriumId = 101,
        auditorium = "101",
        city = "Москва",
        dateStart = "2025-09-01T10:00:00Z",
        dateEnd = "2025-09-01T11:30:00Z",
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
        hash = "hash-$suffix"
    )

    private fun createTestLessonWithHash(hash: String, discipline: String) = Lesson(
        id = "test-id-$hash",
        building = "Корпус А",
        type = "Лекция",
        stream = "Поток 1",
        auditoriumId = 101,
        auditorium = "101",
        city = "Москва",
        dateStart = "2025-09-01T10:00:00Z",
        dateEnd = "2025-09-01T11:30:00Z",
        createdAt = "2025-08-01T00:00:00Z",
        importanceLevel = 1,
        buildingId = 1,
        location = Location(type = "Point", coordinates = listOf(55.75, 37.62)),
        discipline = discipline,
        lessonNumberStart = 1,
        lessonNumberEnd = 2,
        duration = listOf(90),
        kindOfWork = "Лекция",
        isBan = false,
        hash = hash
    )
}

/**
 * Mock экспортер для тестирования
 */
class MockCalendarExporter(
    private val existingEvents: List<CalendarEvent> = emptyList()
) : CalendarExporter {

    private val exportedLessons = mutableListOf<String>()
    private val deletedEvents = mutableListOf<String>()

    override suspend fun export(lessons: List<Lesson>): Map<String, ExportResult> {
        return lessons.associate { lesson ->
            exportedLessons.add(lesson.hash)
            lesson.hash to ExportResult.Success(lesson.hash)
        }
    }

    override suspend fun exportSingle(lesson: Lesson): ExportResult {
        exportedLessons.add(lesson.hash)
        return ExportResult.Success(lesson.hash)
    }

    override suspend fun delete(eventIds: List<String>): Map<String, Boolean> {
        return eventIds.associateWith { eventId ->
            deletedEvents.add(eventId)
            true
        }
    }

    override suspend fun getExistingEvents(): List<CalendarEvent> {
        return existingEvents
    }

    override suspend fun findEventByLessonHash(lessonHash: String): String? {
        return existingEvents.find { it.lessonHash == lessonHash }?.id
    }

    override suspend fun close() {
        // No-op
    }
}

