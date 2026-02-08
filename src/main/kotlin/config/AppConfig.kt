package config

import com.typesafe.config.ConfigFactory

/**
 * Тип экспортера календаря
 */
enum class CalendarType {
    GOOGLE,
    YANDEX,
    BOTH
}

/**
 * Конфигурация приложения, загружаемая из application.yaml
 */
data class AppConfig(
    val hseEmail: String,
    val calendarType: CalendarType,
    val googleConfig: GoogleCalendarConfig,
    val yandexConfig: YandexCalendarConfig,
    val pollingConfig: PollingConfig,
    val retryConfig: RetryConfig
)

data class GoogleCalendarConfig(
    val credentialsFile: String,
    val tokensDirectory: String
)

data class YandexCalendarConfig(
    val email: String,
    val appPassword: String
)

data class PollingConfig(
    val intervalHours: Int,
    val monthsAhead: Int
)

data class RetryConfig(
    val maxAttempts: Int,
    val initialDelaySeconds: Int
)

/**
 * Загружает конфигурацию из application.yaml
 */
fun loadAppConfig(): AppConfig {
    val config = ConfigFactory.load()

    return AppConfig(
        hseEmail = config.getString("hse.email"),
        calendarType = CalendarType.valueOf(
            config.getString("calendar.type").uppercase()
        ),
        googleConfig = GoogleCalendarConfig(
            credentialsFile = config.getString("calendar.google.credentials_file"),
            tokensDirectory = config.getString("calendar.google.tokens_directory")
        ),
        yandexConfig = YandexCalendarConfig(
            email = config.getString("calendar.yandex.email"),
            appPassword = config.getString("calendar.yandex.app_password")
        ),
        pollingConfig = PollingConfig(
            intervalHours = config.getInt("polling.interval_hours"),
            monthsAhead = config.getInt("polling.months_ahead")
        ),
        retryConfig = RetryConfig(
            maxAttempts = config.getInt("retry.max_attempts"),
            initialDelaySeconds = config.getInt("retry.initial_delay_seconds")
        )
    )
}

