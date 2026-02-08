## Plan: Рефакторинг архитектуры HSE Calendar Exporter

Перестроить проект с чёткой архитектурой для поддержки полинга (раз в день), автоматической синхронизации расписания (добавление/удаление пар) и лёгкого переключения между Google/Yandex календарями через конфиг.

### Steps

1. **Создать конфигурационный файл** — расширить [application.yaml](src/main/resources/application.yaml) секцией для настройки экспортера: `hse.email`, `calendar.type` (google/yandex/both), `polling.interval`, креденшлы Yandex/Google
2. **Добавить интерфейс `CalendarExporter`** — создать новый файл `src/main/kotlin/exporter/CalendarExporter.kt` с методами `export(lessons)`, `delete(lessonIds)`, `getExistingEvents()` для унификации экспортеров
3. **Рефакторинг экспортеров** — переместить [GoogleCalendarExporter](src/main/kotlin/Calendar.kt) и [YandexCalendarExporter](src/main/kotlin/YandexExporter.kt) в пакет `exporter/`, реализовать интерфейс `CalendarExporter`, добавить методы удаления и получения существующих событий
4. **Создать сервис синхронизации** — новый файл `src/main/kotlin/sync/TimetableSyncService.kt` с логикой diff (сравнение текущих пар с пара в календаре), удаление отменённых, добавление новых
5. **Рефакторинг полинга** — переписать [TimetableService](src/main/kotlin/Api.kt) в `src/main/kotlin/polling/PollingService.kt`, использовать конфиг для интервала, интегрировать с `TimetableSyncService`
6. **Фабрика экспортеров** — создать `ExporterFactory` для выбора экспортера по конфигу

### Further Considerations

1. **Хранение состояния** — использовать SQLite/файл для хранения ранее синхронизированных пар (для diff) или полагаться на API календаря? Рекомендация: API календаря (проще)
2. **Обработка ошибок** — добавить retry с exponential backoff для API-вызовов? Рекомендация: Да, с лимитом 3 попытки
3. **Интервал полинга** — раз в день или чаще (например, каждые 6 часов)? Рекомендация: конфигурируемый, по умолчанию 24h
