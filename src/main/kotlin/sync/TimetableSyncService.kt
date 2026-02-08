package sync

import exporter.CalendarEvent
import exporter.CalendarExporter
import exporter.ExportResult
import model.Lesson

/**
 * Результат синхронизации
 */
data class SyncResult(
    val added: List<String>,
    val updated: List<String>,
    val deleted: List<String>,
    val errors: List<SyncError>
) {
    val isSuccessful: Boolean get() = errors.isEmpty()

    fun summary(): String = buildString {
        appendLine("=== Результат синхронизации ===")
        appendLine("Добавлено: ${added.size}")
        appendLine("Обновлено: ${updated.size}")
        appendLine("Удалено: ${deleted.size}")
        if (errors.isNotEmpty()) {
            appendLine("Ошибки: ${errors.size}")
            errors.forEach { appendLine("  - ${it.lessonHash}: ${it.message}") }
        }
    }
}

data class SyncError(
    val lessonHash: String,
    val message: String,
    val exception: Exception? = null
)

/**
 * Сервис синхронизации расписания с календарём.
 * Выполняет diff между текущими занятиями и событиями в календаре,
 * добавляет новые, обновляет изменённые и удаляет отменённые.
 */
class TimetableSyncService(
    private val exporter: CalendarExporter
) {

    /**
     * Синхронизирует список занятий с календарём
     * @param lessons Текущие занятия из расписания
     * @return Результат синхронизации
     */
    suspend fun sync(lessons: List<Lesson>): SyncResult {
        println("Начинаем синхронизацию ${lessons.size} занятий...")

        // 1. Получаем текущие события из календаря
        val existingEvents = exporter.getExistingEvents()
        println("Найдено ${existingEvents.size} существующих событий в календаре")

        // 2. Вычисляем diff
        val diff = calculateDiff(lessons, existingEvents)
        println("Diff: добавить ${diff.toAdd.size}, обновить ${diff.toUpdate.size}, удалить ${diff.toDelete.size}")

        val added = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val errors = mutableListOf<SyncError>()

        // 3. Добавляем/обновляем занятия
        diff.toAdd.forEach { lesson ->
            when (val result = exporter.exportSingle(lesson)) {
                is ExportResult.Success -> added.add(lesson.hash)
                is ExportResult.Error -> errors.add(
                    SyncError(lesson.hash, result.message, result.exception)
                )
            }
        }

        diff.toUpdate.forEach { lesson ->
            when (val result = exporter.exportSingle(lesson)) {
                is ExportResult.Success -> updated.add(lesson.hash)
                is ExportResult.Error -> errors.add(
                    SyncError(lesson.hash, result.message, result.exception)
                )
            }
        }

        // 4. Удаляем отменённые события
        if (diff.toDelete.isNotEmpty()) {
            val eventIdsToDelete = diff.toDelete.mapNotNull { event ->
                event.id.takeIf { it.isNotEmpty() }
            }

            val deleteResults = exporter.delete(eventIdsToDelete)
            deleteResults.forEach { (eventId, success) ->
                val event = diff.toDelete.find { it.id == eventId }
                if (success) {
                    deleted.add(event?.lessonHash ?: eventId)
                } else {
                    errors.add(SyncError(event?.lessonHash ?: eventId, "Не удалось удалить событие"))
                }
            }
        }

        return SyncResult(added, updated, deleted, errors)
    }

    /**
     * Вычисляет разницу между текущими занятиями и событиями в календаре
     */
    private fun calculateDiff(
        lessons: List<Lesson>,
        existingEvents: List<CalendarEvent>
    ): DiffResult {
        val lessonHashes = lessons.map { it.hash }.toSet()
        val existingHashes = existingEvents.mapNotNull { it.lessonHash }.toSet()

        // Занятия, которых нет в календаре - нужно добавить
        val toAdd = lessons.filter { it.hash !in existingHashes }

        // Занятия, которые есть и там и там - проверяем на изменения
        val toUpdate = lessons.filter { lesson ->
            val existingEvent = existingEvents.find { it.lessonHash == lesson.hash }
            existingEvent != null && hasChanges(lesson, existingEvent)
        }

        // События, для которых нет соответствующих занятий - нужно удалить
        val toDelete = existingEvents.filter { event ->
            event.lessonHash != null && event.lessonHash !in lessonHashes
        }

        return DiffResult(toAdd, toUpdate, toDelete)
    }

    /**
     * Проверяет, есть ли изменения между занятием и событием в календаре
     */
    private fun hasChanges(lesson: Lesson, event: CalendarEvent): Boolean {
        // Сравниваем ключевые поля
        val summaryChanged = lesson.discipline != event.summary
        val locationChanged = "${lesson.building}, ${lesson.auditorium}" != event.location

        // Сравниваем время (с учётом возможных различий в формате)
        val startChanged = !areDatesEquivalent(lesson.dateStart, event.dateStart)
        val endChanged = !areDatesEquivalent(lesson.dateEnd, event.dateEnd)

        return summaryChanged || locationChanged || startChanged || endChanged
    }

    /**
     * Сравнивает даты с учётом возможных различий в формате
     */
    private fun areDatesEquivalent(date1: String, date2: String): Boolean {
        // Нормализуем даты для сравнения
        val normalized1 = date1.replace("Z", "").replace("T", " ").take(19)
        val normalized2 = date2.replace("Z", "").replace("T", " ").take(19)
        return normalized1 == normalized2
    }

    private data class DiffResult(
        val toAdd: List<Lesson>,
        val toUpdate: List<Lesson>,
        val toDelete: List<CalendarEvent>
    )
}

