# CLAUDE.md

## Что это

Bondcast — Android-приложение для IRL-стриминга: камера → H.264/HEVC → MPEG-TS → **SRT/SRTLA-бондинг** на сервер. Позиционирование: «BELABOX в кармане», совместимость с srtla-экосистемой (belabox cloud, IRLToolkit, self-hosted `srtla_rec`). Живой трекер фич и лог технических решений — в `TODO.md`. Планы по бондинг-серверу — в `docs/`.

## Команды

Сборка/тесты идут через Gradle wrapper. minSdk 35, поэтому debug-сборку ставить/гонять только на устройстве/эмуляторе с API 35+.

```bash
# Собрать приложение (основная проверка после правок)
./gradlew :app:assembleDebug --q

# Собрать/проверить отдельный модуль
./gradlew :feature:stream:assembleDebug --q

# JVM-юнит-тесты (только в pure-Kotlin/JVM-модулях)
./gradlew :feature:bonding:domain:test --q      # протокол + шедулер srtla
./gradlew :feature:obs:testDebugUnitTest --q    # OBS websocket codec/auth

# Один тестовый класс / метод
./gradlew :feature:bonding:domain:test --tests "n7.srtla.SrtlaSchedulerTest" --q
./gradlew :feature:bonding:domain:test --tests "n7.srtla.SrtlaSchedulerTest.reg2*" --q

# JMH-микробенчмарки горячего пути srtla (модуль :benchmark, toolchain 21)
./gradlew :benchmark:jmh --q
./gradlew :benchmark:jmh -PjmhArgs="SchedulerSendBenchmark -prof gc" --q
```

Установка на устройство: `adb install -r -t app/build/outputs/apk/debug/app-debug.apk` (флаг `-t` обязателен — apk debuggable). После запуска выдать разрешения камеры/микрофона и работать в ландшафтной ориентации (`sensorLandscape`).

Отладочный запуск с засевом настроек через intent (см. `MainActivity.SEED_KEYS`):
```bash
adb shell am start -n n7.bondcast/.MainActivity --ez autostart true \
  --ez cfg_bonding true --es cfg_srtla_host 192.168.31.133 --ei cfg_srtla_port 5000
```

## Тулчейн и версии

- Для работы с андройд библиотеками используй последнии версии и android cli инструмент (команда android)
- Версии централизованы в `gradle/libs.versions.toml` (version catalog `libs.*`). Нет `build-logic`/convention-плагинов — каждый модуль конфигурируется у себя, дублирование блоков `android {}` — норма для этого проекта.
- В `gradle.properties` отключены `buildConfig`, `aidl`, `resValues` и др.; `nonTransitiveRClass=true`, `kotlin.incremental=false`, R8 full mode. Configuration cache **выключен**.

## Архитектура модулей

Мультимодульная структура feature-first (`settings.gradle.kts`):

```
:app                      — точка входа, ручной DI, StreamService, навигация Compose
:core:ui                  — тема, общие Compose-компоненты (DiscordUi), ориентация
:feature:bonding:domain   — ЧИСТЫЙ Kotlin: протокол SRTLA + шедулер (n7.srtla.*), explicitApi()
:feature:bonding:impl     — Android-обвязка: сокеты, привязка к сетям, корутины (n7.bondcast.bonding)
:feature:camera           — источники видео CameraX и UVC для StreamPack; вложен :libuvccamera
:feature:stream           — оркестрация стрим-сессии, StreamEngine, StreamScreen
:feature:settings         — DataStore-настройки, экран настроек
:feature:thermal          — мониторинг/митигация троттлинга
:feature:obs              — удалённое управление OBS по websocket (okhttp)
:benchmark                — JMH по горячему пути srtla
```

**Важное разделение bonding.** `:domain` — переносимый Kotlin без Android SDK: конечный автомат `SrtlaScheduler` (регистрация группы, распределение пакетов по линкам, окна/приоритеты), кодек протокола, ABR-контроллер. Он покрыт юнит-тестами (сверен с эталонной C-реализацией) и бенчмарками. Всё, что требует `android.*` (UDP-сокеты, `ConnectivityManager`, привязка сокета к сети), живёт в `:impl`. Пакеты в `:domain` — `n7.srtla.*`, в остальных Android-модулях — `n7.bondcast.*`; namespace модуля и корень пакета могут расходиться (модули кладут файлы в общие пакет-руты вроде `n7.bondcast.ui.components`).

## Ключевые паттерны

**Композиция через интерфейс + декораторы.** Логика собирается цепочкой обёрток `Impl → WithMutex → WithLogging`, а наружу отдаётся lowercase-фабрикой, совпадающей с именем интерфейса. Пример из `SrtlaClient.kt`:
```kotlin
public fun srtlaClient(context: Context): SrtlaClient =
    SrtlaClientWithLogging(SrtlaClientWithMutex(SrtlaClientImpl(context)))
```
Так же устроены `abrController()`, `thermalMonitor()`, `usbCameraMonitor()`, `NetworkProvider`. Добавляя поведение (логирование, синхронизацию, метрики), **создавай новый файл-декоратор**, не раздувай `*Impl`.

**Ручной DI без Dagger/Hilt.** Весь граф — `app/.../AppGraph.kt`, создаётся один раз в `BondcastApp.onCreate()`. Доступ из любого `Context` через `context.appGraph()`. Зависимости передаются конструкторами.

**StreamEngine — шов над пайплайном.** `StreamController` (в `:feature:stream`) оркеструет сессию (фазы `Idle/Connecting/Live/Retrying`, реконнект с exponential backoff, ABR, тепловая митигация, статистика) и говорит только с интерфейсом `StreamEngine`. Единственная реализация — `StreamPackEngine` поверх StreamPack 3.x (энкодер + TS-мукс + SRT-синк через srtdroid). Видео заходит в StreamPack кастомными источниками из `:feature:camera` (`CameraXVideoSource` / `UvcVideoSource`).

**Foreground-сервис развязан от стрима.** `StreamController` держит логику стрима, но не знает про Android-сервис — поднимает/опускает его через интерфейс `StreamForeground`. Реализация в `AppGraph` дергает `StreamService.start/stop`. Сервис (`camera|microphone`) лишь держит процесс живым, стримом не управляет.

**Поток бондинга.** При `bondingEnabled` `StreamController` стартует `srtlaClient` (локальный UDP-relay), получает локальный порт и направляет SRT StreamPack на `127.0.0.1:<port>`; relay раскидывает пакеты по линкам на `srtla_rec`. Relay ленивый и переживает SRT-реконнекты. Без бондинга SRT идёт напрямую на `host:port` из настроек.

**Ориентация.** Стрим-экран живёт в ландшафте (`sensorLandscape` в манифесте), экран настроек принудительно переводится в портрет — см. `LaunchedEffect(showSettings)` в `MainActivity`.

## Соглашения кода

- `:feature:bonding:domain` включает `explicitApi()` — публичные декларации требуют явного `public`. То же по факту в других модулях с публичным API (`StreamEngine`, `StreamController` и т.п.).
- Настройки (`StreamSettings`) персистятся через DataStore Preferences в `SettingsRepository`; читаются как `Flow`.
- Комментарии в коде и доменные термины — на русском, как и коммиты (коротко, 2–10 слов).
- USB-камера (ZV-E1 и подобные UVC) поддержана через вендоренный `:feature:camera:libuvccamera` (Java/NDK) с патчем MJPEG-декода; выбор камеры — только фронт/тыл + USB, отдельные тыловые линзы OEM скрывает.
