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

**android-skills-mcp** (github.com/skydoves/android-skills-mcp) — MCP-сервер поверх официальной библиотеки Android skills от Google. Даёт AI-ассистентам доступ к документации и лучшим практикам Android-разработки без копипаста.

**Установка:**
```bash
claude mcp add android-skills -- npx -y android-skills-mcp
```

**Использование агентом.** Сервер отдаёт три MCP tool-вызова — вызывай их напрямую, не через `android` CLI:
- `search_skills(query)` — найти релевантный skill по ключевым словам (например, «camerax preview» или «edge to edge»)
- `list_skills()` — список всех доступных skill'ов от `android/skills`
- `get_skill(name)` — полный текст `SKILL.md` найденного/выбранного skill'а

Порядок: сначала `search_skills` или `list_skills`, чтобы найти подходящий skill, затем `get_skill` за его содержимым — и уже по нему делать правки в коде. Также доступны как ресурсы `skill://<name>`.

## Архитектура модулей

Мультимодульная структура feature-first (`settings.gradle.kts`):


## Ключевые паттерны

**Композиция через интерфейс + декораторы.** Логика собирается цепочкой обёрток `Impl → WithMutex → WithLogging`, а наружу отдаётся lowercase-фабрикой, совпадающей с именем интерфейса. Пример из `SrtlaClient.kt`:
```kotlin
public fun srtlaClient(context: Context): SrtlaClient =
    SrtlaClientWithLogging(SrtlaClientWithMutex(SrtlaClientImpl(context)))
```
Так же устроены `abrController()`, `thermalMonitor()`, `usbCameraMonitor()`, `NetworkProvider`. Добавляя поведение (логирование, синхронизацию, метрики), **создавай новый файл-декоратор**, не раздувай `*Impl`.

## server/stream — цикл разработки

Серверный стек (SRS + `srtla-rec` + `panel` + опционально `asr-obs`) поднимается через
`server/stream/docker-compose.yml`. Панель — Node.js (`panel/server.js` + `panel/public/`),
но файлы вшиты в образ на этапе сборки (`Dockerfile`), volume их не пробрасывает.

- Правка `panel/server.js` или `panel/public/*` → чтобы проверить, пересобери только образ
  панели: `docker compose up -d --build panel` (из `server/stream`), затем обнови страницу
  в браузере. **Пересобирать `.exe` для этого не нужно.**
- `.exe`-установщик (`installer/setup.iss`, компилируется ISCC.exe из Inno Setup 6) пересобирай
  только когда нужно **раздать** изменения другим людям — он просто копирует те же исходники
  `server/stream/*` (кроме `node_modules`, они ставятся внутри Docker-образа при первом запуске).
- Чтобы новый `.exe` стал доступен по ссылке на лендинге (`releases/latest/download/...`) —
  нужно опубликовать новый GitHub Release (`gh release create vX.Y.Z ...` в `i30mb1/Bondcast`)
  с этим файлом как asset; сам лендинг (`index.html`) ничего не хранит, просто ссылается на
  «latest» релиз.

## Соглашения кода

- Комментарии в коде и доменные термины — на русском, как и коммиты (коротко, 2–10 слов).
- USB-камера (ZV-E1 и подобные UVC) поддержана через вендоренный `:feature:camera:libuvccamera` (Java/NDK) с патчем MJPEG-декода; выбор камеры — только фронт/тыл + USB, отдельные тыловые линзы OEM скрывает.
