package io.github.kroune

import config.loadAppConfig
import exporter.ExporterFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import polling.PollingService

/**
 * Точка входа приложения HSE Calendar Exporter.
 *
 * Приложение:
 * 1. Загружает конфигурацию из application.yaml
 * 2. Создаёт экспортер календаря на основе конфигурации (Google/Yandex/Both)
 * 3. Запускает полинг расписания с заданным интервалом
 * 4. Автоматически синхронизирует расписание с календарём
 */
suspend fun main() {
    println("=== HSE Calendar Exporter v2.0 ===")

    // Загружаем конфигурацию
    val appConfig = try {
        loadAppConfig().also {
            println("Конфигурация загружена:")
            println("  Email: ${it.hseEmail}")
            println("  Тип календаря: ${it.calendarType}")
            println("  Интервал полинга: ${it.pollingConfig.intervalHours} часов")
        }
    } catch (e: Exception) {
        println("Ошибка загрузки конфигурации: ${e.message}")
        println("Проверьте файл application.yaml")
        return
    }

    // Создаём HTTP клиент
    val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }

    // Создаём экспортер на основе конфигурации
    val exporter = ExporterFactory.createExporter(appConfig)
    println("Создан экспортер: ${appConfig.calendarType}")

    // Создаём и настраиваем сервис полинга
    val pollingService = PollingService(
        httpClient = httpClient,
        appConfig = appConfig,
        calendarExporter = exporter
    ).apply {
        onSyncCompleted = { result ->
            println("Синхронизация завершена: добавлено ${result.added.size}, " +
                    "обновлено ${result.updated.size}, удалено ${result.deleted.size}")
        }
        onError = { message, _ ->
            println("Ошибка: $message")
        }
    }

    // Запускаем полинг
    println("Запуск полинга расписания...")
    try {
        pollingService.startPolling().join()
    } catch (e: Exception) {
        println("Критическая ошибка: ${e.message}")
    } finally {
        pollingService.close()
        httpClient.close()
    }
}

