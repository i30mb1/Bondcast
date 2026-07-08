# CLAUDE.md

## Что это

Bondcast — Android-приложение для IRL-стриминга: камера → H.264/HEVC → MPEG-TS → **SRT/SRTLA-бондинг** на сервер. Позиционирование: «BELABOX в кармане», совместимость с srtla-экосистемой (belabox cloud, IRLToolkit, self-hosted `srtla_rec`). Живой трекер фич и лог технических решений — в `TODO.md`. Планы по бондинг-серверу — в `docs/`.

## Тулчейн и версии

- Для работы с андройд библиотеками используй последнии версии и android cli инструмент (команда `android`)

## Android CLI — использование в работе

Android CLI 1.0 — основной инструмент для агентной разработки.

**Навигация и анализ кода:**
- `android studio analyze-file <path>` — анализ файла на ошибки и предупреждения
- `android studio find-declaration <symbol>` — поиск объявления класса/метода/ресурса
- `android studio find-usages <symbol>` — поиск всех использований символа
- `android studio open-file <path>` — открыть файл в Android Studio

**Изучение API и зависимостей:**
- `android docs search <query>` — поиск по документации Android
- `android docs fetch` — загрузка документации
- `android studio version-lookup <artifact>` — поиск версий зависимостей (Maven, Gradle, Compose BOM, AGP, Kotlin, SDK, NDK)

**UI и превью:**
- `android studio render-compose-preview <composable>` — рендер Compose превью

**Скиллы для специализированных задач** (`android skills add --skill=<name>`):
- `camerax` — миграция с Camera1/Camera2 на CameraX
- `testing-setup` — создание тестовой инфраструктуры
- `adaptive` — адаптивный интерфейс под разные устройства
- `styles` — работа с Jetpack Compose Styles API
- `edge-to-edge` — реализация Edge-to-Edge UI
- `migrate-xml-views-to-jetpack-compose` — миграция XML-верстки на Compose
- `perfetto-sql` — анализ Perfetto трасс через SQL-запросы

## Android Skills MCP

**android-skills-mcp** (`@android/mcp`) — MCP-сервер для AI-ассистентов. Предоставляет прямой доступ к документации и лучшим практикам Android-разработки через Model Context Protocol.

**Зачем:** Уменьшает галлюцинации AI при работе с Android API, даёт доступ к актуальной документации и примерам кода.

**Установка:**
```bash
npx @android/mcp
# или через npm
npm install -g @android/mcp
```

**Использование:** Подключается как MCP-сервер в конфигурации AI-клиента. Не требуется для работы Android CLI.

## Архитектура модулей

Мультимодульная структура feature-first (`settings.gradle.kts`):


## Ключевые паттерны

**Композиция через интерфейс + декораторы.** Логика собирается цепочкой обёрток `Impl → WithMutex → WithLogging`, а наружу отдаётся lowercase-фабрикой, совпадающей с именем интерфейса. Пример из `SrtlaClient.kt`:
```kotlin
public fun srtlaClient(context: Context): SrtlaClient =
    SrtlaClientWithLogging(SrtlaClientWithMutex(SrtlaClientImpl(context)))
```
Так же устроены `abrController()`, `thermalMonitor()`, `usbCameraMonitor()`, `NetworkProvider`. Добавляя поведение (логирование, синхронизацию, метрики), **создавай новый файл-декоратор**, не раздувай `*Impl`.

## Соглашения кода

- Комментарии в коде и доменные термины — на русском, как и коммиты (коротко, 2–10 слов).
- USB-камера (ZV-E1 и подобные UVC) поддержана через вендоренный `:feature:camera:libuvccamera` (Java/NDK) с патчем MJPEG-декода; выбор камеры — только фронт/тыл + USB, отдельные тыловые линзы OEM скрывает.
