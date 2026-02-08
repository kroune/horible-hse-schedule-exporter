package polling

import config.AppConfig
import config.RetryConfig
import exporter.CalendarExporter
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import model.Lesson
import org.slf4j.LoggerFactory
import sync.SyncResult
import sync.TimetableSyncService
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Результат получения расписания
 */
sealed class TimetableResult {
    data class Success(val lessons: List<Lesson>) : TimetableResult()
    data class Error(val message: String, val exception: Exception? = null) : TimetableResult()
}

/**
 * Сервис полинга расписания.
 * Периодически получает расписание и синхронизирует его с календарём.
 */
class PollingService(
    private val httpClient: HttpClient,
    private val appConfig: AppConfig,
    private val calendarExporter: CalendarExporter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val syncService = TimetableSyncService(calendarExporter)
    private var pollingJob: Job? = null

    /**
     * Callback для обработки результатов синхронизации
     */
    var onSyncCompleted: ((SyncResult) -> Unit)? = null

    /**
     * Callback для обработки ошибок
     */
    var onError: ((String, Exception?) -> Unit)? = null

    /**
     * Запускает полинг расписания
     */
    fun startPolling(): Job {
        pollingJob?.cancel()

        pollingJob = scope.launch {
            val intervalHours = appConfig.pollingConfig.intervalHours
            println("Запуск полинга с интервалом $intervalHours часов")

            while (isActive) {
                try {
                    performSync()
                } catch (e: Exception) {
                    val message = "Ошибка при синхронизации: ${e.message}"
                    println(message)
                    onError?.invoke(message, e)
                }

                println("Следующая синхронизация через $intervalHours часов")
                delay(intervalHours.hours)
            }
        }

        return pollingJob!!
    }

    /**
     * Останавливает полинг
     */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        println("Полинг остановлен")
    }

    /**
     * Выполняет одну итерацию синхронизации
     */
    suspend fun performSync(): SyncResult? {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val endDate = today + DatePeriod(months = appConfig.pollingConfig.monthsAhead)

        println("Получаем расписание с $today по $endDate для ${appConfig.hseEmail}")

        return when (val result = fetchTimetable(appConfig.hseEmail, today, endDate)) {
            is TimetableResult.Success -> {
                println("Получено ${result.lessons.size} занятий")
                val syncResult = syncService.sync(result.lessons)
                println(syncResult.summary())
                onSyncCompleted?.invoke(syncResult)
                syncResult
            }

            is TimetableResult.Error -> {
                val message = "Ошибка при получении расписания: ${result.message}"
                println(message)
                onError?.invoke(message, result.exception)
                null
            }
        }
    }

    /**
     * Получает расписание из API HSE
     */
    suspend fun fetchTimetable(
        email: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): TimetableResult {
        return withRetry(appConfig.retryConfig) {
            try {
                val response = httpClient.get("https://api.hseapp.ru/v3/ruz/lessons") {
                    parameter("email", email)
                    parameter("start", startDate.toString())
                    parameter("end", endDate.toString())
                    headers {
                        append("Accept-Language", "ru-RU, ru;q=0.9, en-US;q=0.8, en;q=0.7")
                        append("User-Agent", "hse-calendar-exporter@2.0.0")
                    }
                }

                if (response.status.value in 200..299) {
                    TimetableResult.Success(response.body())
                } else {
                    val errorMessage = "HTTP ${response.status.value}: ${response.bodyAsText()}"
                    logger.error(errorMessage)
                    TimetableResult.Error(errorMessage)
                }
            } catch (e: ClientRequestException) {
                logger.error("Request error: ${e.response.status}", e)
                TimetableResult.Error("Ошибка запроса: ${e.response.status}", e)
            } catch (e: Exception) {
                logger.error("Network error: ${e.message}", e)
                TimetableResult.Error("Сетевая ошибка: ${e.message}", e)
            }
        }
    }

    /**
     * Выполняет операцию с retry и exponential backoff
     */
    private suspend fun <T> withRetry(
        config: RetryConfig,
        operation: suspend () -> T
    ): T {
        var lastException: Exception? = null
        var delay = config.initialDelaySeconds.seconds

        repeat(config.maxAttempts) { attempt ->
            try {
                return operation()
            } catch (e: Exception) {
                lastException = e
                if (attempt < config.maxAttempts - 1) {
                    println("Попытка ${attempt + 1} не удалась, повтор через $delay: ${e.message}")
                    delay(delay)
                    delay *= 2
                }
            }
        }

        throw lastException ?: RuntimeException("Неизвестная ошибка после ${config.maxAttempts} попыток")
    }

    /**
     * Закрывает ресурсы
     */
    suspend fun close() {
        stopPolling()
        calendarExporter.close()
    }

    private val logger = LoggerFactory.getLogger(PollingService::class.java)
}
