package exporter

import model.Lesson

/**
 * Представляет событие в календаре
 */
data class CalendarEvent(
    val id: String,
    val summary: String,
    val description: String?,
    val location: String?,
    val dateStart: String,
    val dateEnd: String,
    val lessonHash: String?
)

/**
 * Результат операции с календарем
 */
sealed class ExportResult {
    data class Success(val eventId: String) : ExportResult()
    data class Error(val message: String, val exception: Exception? = null) : ExportResult()
}

/**
 * Унифицированный интерфейс для экспортеров календаря.
 * Позволяет легко переключаться между Google/Yandex календарями.
 */
interface CalendarExporter {

    /**
     * Экспортирует список занятий в календарь
     * @param lessons Список занятий для экспорта
     * @return Map из lesson.hash в результат экспорта
     */
    suspend fun export(lessons: List<Lesson>): Map<String, ExportResult>

    /**
     * Экспортирует одно занятие в календарь
     * @param lesson Занятие для экспорта
     * @return Результат экспорта
     */
    suspend fun exportSingle(lesson: Lesson): ExportResult

    /**
     * Удаляет события из календаря по их идентификаторам
     * @param eventIds Список идентификаторов событий для удаления
     * @return Map из eventId в результат удаления (true = успешно)
     */
    suspend fun delete(eventIds: List<String>): Map<String, Boolean>

    /**
     * Получает все существующие события из календаря
     * (созданные этим экспортером, определяемые по префиксу или метаданным)
     * @return Список событий в календаре
     */
    suspend fun getExistingEvents(): List<CalendarEvent>

    /**
     * Проверяет, существует ли событие с данным hash занятия
     * @param lessonHash Hash занятия
     * @return ID события или null, если не найдено
     */
    suspend fun findEventByLessonHash(lessonHash: String): String?

    /**
     * Закрывает ресурсы экспортера (HTTP клиенты и т.д.)
     */
    suspend fun close()
}

