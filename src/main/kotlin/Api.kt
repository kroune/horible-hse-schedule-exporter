import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.model.Event
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import java.util.Calendar
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// Sealed класс для обработки ошибок
sealed class Result<out T> {
    data class Success<out T>(val data: T) : Result<T>()
    data class Error(val exception: Exception) : Result<Nothing>()
}

// Кастомные исключения
class BadRequestException : Exception("Bad request")
class NetworkException : Exception("Network error")

typealias TimetableResponse = List<Lesson>

class TimetableService(
    private val client: HttpClient,
    private val email: String,
) {

    private val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e ->
        println("Ошибка в пулинге: ${e.message}")
    })

    fun startPolling(): Job {
        return scope.launch {
            while (isActive) {
                val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                val startDate = today
                val endDate = today + DatePeriod(months = 1) // Получаем расписание на месяц вперед

                when (val result = getTimetable(email, startDate, endDate)) {
                    is Result.Success -> processTimetable(result.data)
                    is Result.Error -> println("Ошибка при получении расписания: ${result.exception}")
                }

                delay(1.hours) // Повторяем каждый час
            }
        }
    }


    private suspend fun processTimetable(timetable: TimetableResponse) {
        // Обработка полученного расписания
        println("Получено ${timetable.size} занятий")
        timetable.forEach { lesson ->
            println("Дисциплина: ${lesson.discipline}, Аудитория: ${lesson.auditorium}")
        }
        val yandexExporter = YandexCalendarExporter(email, "ewhtujhavynideyk")

        timetable.forEach { lesson ->
            val success = yandexExporter.exportLessonToCalendar(lesson)
            if (success) {
                println("Успешно экспортировано занятие: ${lesson.discipline}")
            } else {
                println("Не удалось экспортировать занятие: ${lesson.discipline}")
            }
        }

        yandexExporter.close()
//        GoogleCalendarExporter.exportLessonsToCalendar(timetable)
    }

    suspend fun getTimetable(
        email: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Result<TimetableResponse> {
        return try {
            val response = client.get("https://api.hseapp.ru/v3/ruz/lessons") {
                parameter("email", email)
                parameter("start", startDate.toString())
                parameter("end", endDate.toString())
                headers {
                    append("Accept-Language", "ru-RU, ru;q=0.9, en-US;q=0.8, en;q=0.7")
                    append("User-Agent", "hse-ical@1.0.0")
                }
            }

            println(response.bodyAsText())
            Result.Success(response.body())
        } catch (e: ClientRequestException) {
            when (e.response.status.value) {
                400 -> Result.Error(BadRequestException())
                else -> Result.Error(e)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.Error(NetworkException())
        }
    }
}
