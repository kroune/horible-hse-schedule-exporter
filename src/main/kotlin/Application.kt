package io.github.kroune

import GoogleCalendarExporter
import TimetableService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

// Пример использования
suspend fun main() {
    val client = HttpClient {
        install(ContentNegotiation) {
            this.json(
                Json
            )
        }
    }

    val service = TimetableService(client, "masvetlichnyy@edu.hse.ru").startPolling().join()
//    val result = service.getTimetable(
//        email = "masvetlichnyy@edu.hse.ru",
//        startDate = LocalDate(2025, 9, 1),
//        endDate = LocalDate(2025, 10, 1)
//    )
//
//    when (result) {
//        is Result.Success -> {
//            println("Received ${result.data.size} lessons")
//        }
//        is Result.Error -> println("Error: ${result.exception.message}")
//    }
}