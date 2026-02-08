package exporter

import config.AppConfig
import config.CalendarType
import model.Lesson

/**
 * Фабрика для создания экспортеров календаря на основе конфигурации
 */
object ExporterFactory {

    /**
     * Создаёт экспортер на основе типа из конфигурации
     */
    fun createExporter(config: AppConfig): CalendarExporter {
        return when (config.calendarType) {
            CalendarType.GOOGLE -> GoogleCalendarExporter(
                config = config.googleConfig,
                retryConfig = config.retryConfig
            )
            CalendarType.YANDEX -> YandexCalendarExporter(
                config = config.yandexConfig,
                retryConfig = config.retryConfig
            )
            CalendarType.BOTH -> CompositeCalendarExporter(
                exporters = listOf(
                    GoogleCalendarExporter(config.googleConfig, config.retryConfig),
                    YandexCalendarExporter(config.yandexConfig, config.retryConfig)
                )
            )
        }
    }
}

/**
 * Композитный экспортер, который экспортирует в несколько календарей одновременно
 */
class CompositeCalendarExporter(
    private val exporters: List<CalendarExporter>
) : CalendarExporter {

    override suspend fun export(lessons: List<Lesson>): Map<String, ExportResult> {
        val results = mutableMapOf<String, ExportResult>()

        lessons.forEach { lesson ->
            val exportResults = exporters.map { exporter ->
                exporter.exportSingle(lesson)
            }

            // Если хотя бы один экспорт успешен, считаем успехом
            val successResult = exportResults.filterIsInstance<ExportResult.Success>().firstOrNull()
            val errorResults = exportResults.filterIsInstance<ExportResult.Error>()

            results[lesson.hash] = when {
                successResult != null -> successResult
                errorResults.isNotEmpty() -> ExportResult.Error(
                    "Все экспортеры завершились с ошибками: ${errorResults.joinToString { it.message }}"
                )
                else -> ExportResult.Error("Неизвестная ошибка")
            }
        }

        return results
    }

    override suspend fun exportSingle(lesson: Lesson): ExportResult {
        val results = exporters.map { it.exportSingle(lesson) }

        return results.filterIsInstance<ExportResult.Success>().firstOrNull()
            ?: results.filterIsInstance<ExportResult.Error>().firstOrNull()
            ?: ExportResult.Error("Неизвестная ошибка")
    }

    override suspend fun delete(eventIds: List<String>): Map<String, Boolean> {
        val combinedResults = mutableMapOf<String, Boolean>()

        eventIds.forEach { eventId ->
            val deleteResults = exporters.map { exporter ->
                exporter.delete(listOf(eventId))[eventId] ?: false
            }
            // Успех, если хотя бы один экспортер удалил
            combinedResults[eventId] = deleteResults.any { it }
        }

        return combinedResults
    }

    override suspend fun getExistingEvents(): List<CalendarEvent> {
        return exporters.flatMap { it.getExistingEvents() }.distinctBy { it.lessonHash }
    }

    override suspend fun findEventByLessonHash(lessonHash: String): String? {
        return exporters.firstNotNullOfOrNull { it.findEventByLessonHash(lessonHash) }
    }

    override suspend fun close() {
        exporters.forEach { it.close() }
    }
}

