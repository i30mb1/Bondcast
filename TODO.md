# Bondcast — план и трекер фич

**Видение:** мобильный IRL-стример для Android: камера → H.264/HEVC → MPEG-TS → **SRT/SRTLA-бондинг** на сервер.
Позиционирование: «BELABOX в кармане» — совместимость с существующей srtla-инфраструктурой (BELABOX Cloud, IRLToolkit, self-hosted srtla_rec) без рюкзака с железом.

**Как вести файл:** фичи — чекбоксами `- [ ]` / `- [x]` внутри фаз. Новые идеи — в «Бэклог». Принятые технические решения — в «Лог решений» с датой.

---

## Технологии (решено заранее)

| Область | Выбор | Почему |
|---|---|---|
| Язык / UI | Kotlin 2.3, Coroutines/Flow, Jetpack Compose + Material3 | уже в проекте |
| Камера | **CameraX** | современный стек, лайфсайклы из коробки; решено |
| Пайплайн стриминга | **StreamPack 3.x** как энкодер + TS-муксер + SRT-синк; видео заходит из CameraX через кастомный видеоисточник (API источников v3) | готовый TS-муксер и SRT-синк, аппаратные кодеки, Apache-2.0. Оборачиваем в свой интерфейс `StreamEngine`; если связка CameraX↔StreamPack окажется хрупкой — свой конвейер CameraX → OpenGL → MediaCodec, от StreamPack остаются муксер и эндпоинт |
| SRT | **srtdroid** (обёртка над libsrt 1.5.x) | боевой libsrt, богатая статистика (RTT, loss, sndBuf) — нужна для адаптивного битрейта |
| Бондинг | **SRTLA** (протокол BELABOX): своя Kotlin-реализация `srtla_send` в `:kotlin` (без NDK), локальный UDP-прокси: libsrt → 127.0.0.1 → srtla_send → N сетей → srtla_rec на сервере | совместимость со всей belabox-экосистемой. Привязка UDP-сокетов к сетям — `ConnectivityManager.requestNetwork` (cellular+wifi+ethernet) + `Network.bindSocket(DatagramSocket)` (чистый Kotlin) |
| Видео | H.264 (MVP) → HEVC → AV1 (когда появится hw encode массово) | |
| Аудио | AudioRecord → AAC-LC 128k (Opus — в бэклоге) | |
| Фоновая работа | Foreground Service (`camera\|microphone`), WakeLock | стрим обязан жить при выключенном экране/сворачивании |
| Настройки | Jetpack DataStore (Preferences) | |
| DI | вручную (граф маленький) | Hilt — только если разрастёмся |
| Модули | `:android` — приложение; `:kotlin` — чистая JVM-логика (протокол srtla v2-своя, ABR-алгоритмы, парсеры) с быстрыми unit-тестами | модуль `:kotlin` уже есть |
| Дев-сервер | **SRS v6** (docker `ossrs/srs:v6.0-r0`); готовые конфиг и инструкция: `D:\AndroidProject\docs\stream` | SRT-ингест `srt://<IP>:10080`, streamid `#!::r=live/<name>,m=publish`, серверная latency 1500, tlpktdrop; просмотр: HTTP-FLV `http://<IP>:8080/live/<name>.flv`, HLS, RTMP; для бондинга ставим `srtla_rec` перед SRS |
| CI | GitHub Actions: assemble + unit tests + lint | |

Отклонено: нативный SRT bonding (socket groups libsrt) — experimental, серверами почти не поддержан; RootEncoder — свой SRT на Kotlin без глубокой статистики libsrt; RTMP как основной протокол — не живёт на нестабильных сетях.

**Рабочий процесс:** исследование Android — через **Android CLI 1.0** (`android`; скиллы: `android skills list` / `android skills add <имя>`; установлены `android-cli`, `camera1-to-camerax`, `testing-setup`; команда эмулятора в 1.0 на Windows отключена) и adb (`C:\Users\eug79\AppData\Local\Android\Sdk\platform-tools\adb.exe`, в PATH его нет), сборка — `.\gradlew`. Дев-сервер SRS поднимается по инструкции `D:\AndroidProject\docs\stream\srs-srt-configurations.md`, после правки `srs.conf` контейнер пересоздаётся.

---

## Фаза 0 — каркас проекта

Готово: Gradle-скелет (AGP 9, version catalog, R8+proguard), Compose+Material3, Android CLI 1.0, переименование в `n7.bondcast`.

- [ ] GitHub Actions: сборка + тесты на PR

## Фаза 1 — MVP: камера → SRT на сервер

Критерий готовности: 1080p30 @ 4.5 Mbps H.264 + AAC летит час без падения на SRS (просмотр по HTTP-FLV), задержка ~2 с, стрим переживает сворачивание приложения.

Готово: разрешения, полноэкранное превью, видеоисточник для StreamPack, `StreamEngine` (prepare/startStream/awaitDisconnect/stopStream/bindPreview/readStats), кодирование H.264+AAC → MPEG-TS → SRT caller, экран настроек (DataStore), foreground service, HUD со статистикой, реконнект с exponential backoff.

- [ ] Поднять SRS по инструкции из `D:\AndroidProject\docs\stream`, прогнать стрим: с эмулятора сервер = `10.0.2.2`, просмотр `http://localhost:8080/live/<name>.flv`; здоровье связи — ровный `ikbps` в `docker logs -f srs`
- [ ] Ручной прогон: час стрима на реальном устройстве

## Фаза 2 — надёжность и удобство

- [ ] Переключение камер (фронт/тыл), зум жестом, тап-фокус, фонарик
- [ ] Mute микрофона
- [ ] Адаптивный битрейт v1: простая лестница вниз/вверх по SRT-статистике (sndBuf, RTT, loss)
- [ ] Подробная статистика соединения (график битрейта/RTT)
- [ ] Локальная запись параллельно стриму (в т.ч. в лучшем качестве, чем стрим)
- [ ] Автостарт стрима после смерти процесса/перезагрузки (опция)
- [ ] Bluetooth-микрофон
- [ ] Решить minSdk перед публикацией (сейчас 35 = только Android 15+; для охвата, вероятно, 29)
- [x] Прямой RTMP в Twitch без своего сервера (форсит H.264, без бондинга) — переключатель в настройках

## Фаза 3 — SRTLA-бондинг (флагманская фича)

Подход: своя Kotlin-реализация srtla в `:kotlin` (без NDK — `Network.bindSocket` закрывает привязку сокета к сети). Два уровня: сети телефона + Bondlink (соседнее устройство). Детальный план и этапы M0–M5 — [docs/srtla-plan.md](docs/srtla-plan.md).

Готово: M0 (протокол-слой + шедулер, сверено с C), M1 (локальный UDP-relay + IO-цикл + UI бондинга), M2 (захват сетей + `Network.bindSocket` + failover), M3a (per-link панель в HUD); e2e-стенд с `srtla_rec` перед SRS поднят ([docs/srtla-rec.md](docs/srtla-rec.md)) и прогнан с телефона (WiFi-линк регистрируется и несёт поток).

- [ ] M3b: вкл/выкл линка и приоритеты из UI (scheduler API уже есть), per-link RTT/loss (нужны таймстампы в keepalive)
- [ ] M4: Bondlink — соседнее устройство как relay-аплинк (NSD + pairing + прозрачный форвардер)
- [ ] M5: бондированный спидтест перед стримом + ABR v2 (битрейт от суммарной полосы всех линков)
- [ ] Тест деградации на публично доступном srtla_rec (отключаем WiFi посреди стрима — cellular подхватывает): нужен проброс UDP 5000 до 93.84.96.193 или VPS
- [ ] Проверка совместимости с BELABOX Cloud

## Фаза 4 — качество картинки и звука

- [ ] HEVC (с фолбэком на H.264)
- [ ] 60 fps, выбор битрейта до 12+ Mbps
- [ ] Термоменеджмент: `getThermalHeadroom` → авто-ступени fps/разрешения, индикатор перегрева
- [ ] Opus-аудио в TS (опция), выбор битрейта/семплрейта
- [ ] Стабилизация (EIS), выбор объектива (ultrawide/tele)

## Фаза 5 — стримерские фичи

- [ ] Чат Twitch / YouTube / Kick единой лентой поверх превью
- [ ] TTS чата и алертов в наушник (главная IRL-фича «руки заняты»)
- [ ] Privacy-кнопка: мгновенно чёрный экран/заглушка + mute одним тапом
- [ ] BRB-режим: при деградации сети автоматически статичная картинка + низкий битрейт вместо каши
- [ ] Оверлеи: часы, GPS-скорость/карта, пульс (BLE-датчик), текст — вшиваются в картинку. Burn-in **исследован и отложен** (упёрлись в StreamSharing+эффект CameraX); путь — свой `SurfaceProcessor`. План и результаты: [docs/camerax-overlay-plan.md](docs/camerax-overlay-plan.md)
- [ ] Replay-буфер: сохранить последние N секунд кнопкой
- [ ] Remote control: веб-дашборд для «режиссёра» (статы, смена битрейта/сцены удалённо)
- [ ] Управление командами чата (модеры могут включить BRB и т.п.)
- [ ] Wear OS: старт/стоп, статы, privacy-кнопка с руки

## Фаза 6 — экосистема

- [ ] **Bondlink**: второй телефон как дополнительный канал бондинга (аналог Moblink) — перенесено в план SRTLA как этап M4 ([docs/srtla-plan.md](docs/srtla-plan.md))
- [ ] Внешние камеры: UVC (USB), DJI/GoPro по WiFi
- [ ] Свой облачный relay: srtla_rec + рестрим на Twitch/YT/Kick + DVR + серверные оверлеи (монетизация)
- [ ] Simulcast на несколько площадок (через свой relay)
- [ ] Интеграция с noalbs / IRLToolkit (авто-переключение сцен по health)

## Бэклог / идеи без фазы

- [ ] **Concurrent Camera** (CameraX 1.7): одновременно фронт+тыл через `bindToLifecycle(List<SingleCameraConfig>)`, есть визуальный стайлинг рамок (`setRoundedCornerRatio`, `setBorderWidthRatio`/`setBorderColor` из 1.7.0-alpha02) — «reaction cam» a-ля TikTok LIVE dual. Не проверено на железе, отдельная фича, не инкремент к текущей камера-панели.
- [ ] Следить за srtla2 и RIST-бондингом — добавить, когда стабилизируются
- [ ] AV1 hw encode на новых SoC
- [ ] Экспорт логов сессии для разбора проблем
- [ ] Геймпад/BT-пульт: маппинг кнопок (старт, privacy, маркер)
- [ ] Пресеты под площадки (Twitch 6 Mbps cap и т.д.)
- [ ] Обучение пользователя: OEM battery killers (dontkillmyapp), рекомендации по питанию

## Риски

- MediaCodec-квирки по вендорам (Samsung/Pixel/Xiaomi ведут себя по-разному) — тестировать на 2–3 устройствах
- Связка CameraX ↔ StreamPack не задокументирована — спайк в начале Фазы 1; запасной путь: свой GL-конвейер
- OEM-убийцы фоновых процессов — foreground service + инструкции пользователю
- Перегрев при 1080p60/HEVC — термоменеджмент обязателен (Фаза 4)
- srtla — форк на C, надо поддерживать самим; артефакты StreamPack/srtdroid уточнить на момент подключения
- Политика Play для camera foreground service — заранее подготовить обоснование

## Лог решений

- **2026-07-22**: **Прямой RTMP в Twitch без своего сервера** — доп. таргет в `StreamSettings` (`twitchDirectEnabled`/`twitchStreamKey`/`twitchIngestUrl`), НЕ замена SRT/SRTLA-конвейера (тот остаётся дефолтом). У StreamPack 3.1.2 уже есть готовый `RtmpEndpointFactory`+`RtmpMediaDescriptor` (артефакт `streampack-rtmp`, сам муксит во FLV, без своего sink'а в отличие от SRT-пути с `SendTimeSrtSink`) — `StreamPackEngine` пересоздаёт `SingleStreamer` при смене таргета (эндпоинт вморожен в конструктор `cameraSingleStreamer`, налету не меняется). Twitch RTMP не берёт HEVC → форсим H.264 только для этого таргета на уровне энджайна (UI кодек по-прежнему не показывает). Бондинг форсится выключенным (`StreamController`), т.к. Twitch — одно TCP-соединение, объединять там нечего; `readStats()`/ABR на этом таргете тихо no-op (`RtmpEndpoint.metrics` — `TODO()`, ловится `runCatching`). Ранний отказ от «RTMP как основного протокола» (см. таблицу технологий выше) не пересмотрен — это опциональный fallback-путь для тех, у кого нет своего сервера, не замена бондинга. Пульт OBS в этом режиме скрыт (предполагает свою машину-сервер рядом, тут её нет). Ключ трансляции можно не копировать руками: добавлен скоуп `channel:read:stream_key` к DCF-логину Twitch (`TwitchSession.streamKey()`, Helix `GET /streams/key`) и кнопка «Взять из Twitch» в настройках — уже залогиненным (для чата) юзерам нужен один разовый релогин, т.к. Twitch не расширяет скоуп у выданного токена.
- **2026-07-08**: **Камера-панель: стабилизация/AE-AWB-lock/LLB/экспозиция/тап-фокус/pinch-zoom добавлены поверх CameraX 1.7.0-alpha02**, миграция `UseCaseGroup`→`SessionConfig`. По ходу разобрали официальный changelog CameraX и перепроверили API по sources jar (не по javap/доке — доке верить нельзя не глядя): (1) `CameraXViewfinder` из `camera-compose` **умеет** встроенные жесты через параметры `isTapToFocusEnabled`/`isPinchToZoomEnabled` (вторая, не-deprecated перегрузка) — используем их вместо своей обвязки на `detectTapGestures`/`detectTransformGestures` (у библиотеки корректный sensor-to-buffer трансформ через `CameraInfoInternal.sensorRect`, наша ручная версия игнорировала crop/поворот). (2) `FocusMeteringAction.setLockingMode` — не отдельный enum, а те же `FLAG_AF/FLAG_AE/FLAG_AWB`; AE/AWB-lock теперь через это (стабильный публичный API), не через `Camera2CameraControl`+`CaptureRequestOptions` (экспериментальный camera2-interop). (3) **CameraX Extensions** (`ExtensionsManager`, `ExtensionMode.HDR/NIGHT/BOKEH`) — судя по докам в sources jar, эффект применяется и к живому `Preview`, не только к `ImageCapture` (`VideoCapture` может забрать тот же улучшенный Preview-стрим) — потенциально применимо к нашему пайплайну (кодируем именно Preview). Но: HDR/NIGHT исторически заточены под фото (мульти-кадровая склейка, может быть медленно на превью), доступность по вендорам не гарантирована, и не проверена совместимость с нашей связкой 2×`Preview`+feature-group — нужен спайк на реальном железе, не коммитим вслепую.
- **2026-07-03**: StreamPack + srtdroid вместо своего пайплайна — скорость MVP; изолируем за `StreamEngine`, чтобы можно было заменить. Бондинг — SRTLA (совместимость с belabox-экосистемой), реализация NDK-портом `srtla_send`; своя Kotlin-реализация — возможная v2. SRT socket groups отклонены (experimental, нет серверов).
- **2026-07-03**: Дев/прод-сервер — **SRS v6** с готовым конфигом из `D:\AndroidProject\docs\stream` (вместо MediaMTX); для бондинга `srtla_rec` встанет перед SRS. Камера — **CameraX** (решение владельца); StreamPack остаётся слоем энкодер/муксер/SRT, интеграция через кастомный видеоисточник проверяется спайком. Исследование Android — через Android CLI 1.0 (`android` + агентские скиллы) и adb/gradlew, не через Studio.
- **2026-07-03**: Найден и починен deadlock регистрации srtla: `pendingReg2` навсегда прилипал к первому линку (cellular, недостижимый до LAN), т.к. дедлайн обновлялся каждым ресендом REG1. Теперь дедлайн фиксируется при выборе кандидата, REG1 ротируется по линкам (тест `reg1RotatesToNextLinkWhenRegistrationStalls`). Для CLI-прогонов добавлены intent-extras `cfg_*` + `autostart` (заливают настройки без UI). HUD показывает srtla-адрес при включённом бондинге.
- **2026-07-03**: SRTLA — **пересмотр: своя Kotlin-реализация в `:kotlin` вместо NDK-порта `srtla_send`**. Единственный весомый довод за NDK (привязка UDP-сокета к сети через `android_setsocknetwork()`) снят — публичный `Network.bindSocket(DatagramSocket)` делает это из Kotlin; протокол мал и юнит-тестируем, Bondlink-relay всё равно кастомный. Два уровня бондинга (сети телефона + соседнее устройство), этапы M0–M5 — [docs/srtla-plan.md](docs/srtla-plan.md). srtla_send — прозрачный локальный UDP-прокси, `srtla_rec` перед SRS ([docs/srtla-rec.md](docs/srtla-rec.md)).
- **2026-07-03**: Камера в MVP — по факту **Camera2** (встроенный источник StreamPack `cameraSingleStreamer`), не CameraX. CameraX — обёртка над Camera2, для стабильности/качества стрима не даёт выигрыша (это уровень сети/энкодера), а нужные настройки (экспозиция/ISO/WB/фокус/стабилизация/зум/фонарик + raw `set(CaptureRequest.Key)`) уже доступны через StreamPack `CameraSettings`. Строку CameraX в таблице выше считать неактуальной.
- **2026-07-05**: **ZV-E1 (внешняя UVC USB-камера) как видеоисточник — видео заработало.** Баг был трёхслойным (каждый слой маскировал следующий). Вендорнули herohan в модуль `:libuvccamera` (исходники `github.com/shiyinghan/UVCAndroid`, он же источник AAR `com.herohan:UVCAndroid`) и патчили нативку; в проект подключён как `implementation(project(":libuvccamera"))` вместо AAR (сборка ndkBuild/NDK 27.3, 4 ABI, AGP 9 требует `proguard-android-optimize.txt`). Слои:
  1. **Чёрное видео.** herohan декодит MJPEG строгим TurboJPEG `uvc_mjpeg2rgbx_tj` без вставки таблиц Хаффмана; ZV-E1 (как почти все UVC) шлёт MJPEG без DHT-маркера → `tjDecompress2` молча падает (ошибка логируется только через `DEBUG_TJ`=`LOGD`, а в релизной `.so` `LOGD` скомпилен в no-op → «тишина»). Кадр `recycle`-ится, ничего не доходит до surface/callback. **Фикс:** в `UVCPreview.cpp:do_preview` MJPEG-ветка `uvc_mjpeg2rgbx_tj` → `uvc_mjpeg2rgbx` (libjpeg-путь из `libuvc/src/frame-mjpeg.c` с `insert_huff_tables()`, тот же RGBX-выход).
  2. **Видео узкой полосой сверху, ниже ровный серый.** libuvc выбирает BULK-передачу, если у VideoStreaming-интерфейса один altsetting (`stream.c:261` `isochronous = num_altsetting > 1`) — у ZV-E1 так. Для bulk размер буфера = `dwMaxPayloadTransferSize`, а он заклампан в **16384** (`stream.c:313`, «Android bulk/usbfs workaround»). MJPEG-кадр ZV-E1 крупный (~0.5-1 МБ!), поэтому один кадр читается кусками по 16КБ, и `_uvc_process_payload` парсит UVC-заголовок в КАЖДОМ куске — хотя заголовок только в первом (камера шлёт кадр одним payload до короткого пакета) → ложные FID-переключения рвут JPEG, libjpeg декодит верхние строки и добивает остаток серым. **Фикс:** в `stream.c` (блок bulk `libusb_fill_bulk_transfer`) размер буфера = `dwMaxVideoFrameSize` (floor `dwMaxPayloadTransferSize`) → один bulk-transfer = целый кадр.
  3. **RendererHolder herohan рисует кадр в slave-surface полосой** (баг GL-слоя в связке со StreamPack; воспроизводился даже при slave==primary 1280×720, `setDefaultBufferSize`/previewSize не помогли). **Обошли целиком:** не используем `addSurface`, а берём декодированный кадр через `IFrameCallback(UVCCamera.PIXEL_FORMAT_RGBX)` и сами рисуем на surface StreamPack + on-screen превью через `lockCanvas`/`drawBitmap` (surface StreamPack локается как CPU-продюсер, GL не нужен). См. `android/.../uvc/UvcVideoSource.kt`.
  - **Диагностика, которая распутала (для будущего):** сохранять сырой кадр в PNG на каждом уровне пайплайна — (а) ImageReader-slave показал полосу от RendererHolder; (б) PNG из `IFrameCallback` показал, что уже декодированный кадр — полоса (значит выше RendererHolder); (в) нативный лог `frame_mjpeg->data_bytes` + первые/последние 2 байта. Решающим был именно (в): куски 16КБ, только первый начинался `FFD8`, ни один не кончался `FFD9` → фрагментация bulk. После фикса — `dbytes≈500-995КБ, first=ffd8 last=ffd9`. adb держать по WiFi (`adb tcpip 5555`), чтобы единственный USB-C порт телефона был свободен под камеру; ZV-E1 периодически сама уходит в MTP/сон — переключать в «USB Streaming» в меню. Полный контекст и API herohan — в памяти `usb-camera-zve1.md`.
- **2026-07-08**: **Burn-in оверлеи (карта) — исследованы и ОТЛОЖЕНЫ; захват на CameraX работает (встроенная + USB), эфир+превью живые.** Штатный `OverlayEffect` даёт «превью + эфир + оверлей одновременно» **недостижимо**: два `Preview` одного таргета + эффект → CameraX включает StreamSharing, а встроенный `SurfaceProcessorImpl` одно-выходной по дизайну (проверено грепом исходников 1.7.0-alpha02) → MediaCodec-ветка захлёбывается `EglImage dataspace changed`, видео не идёт на бэк (~205 кбит/с, аудио). `queueDepth`, кодек (H.264/HEVC) и апгрейд до 1.7 не лечат; Google фиксит только `PREVIEW|VIDEO_CAPTURE` (нам недоступно — StreamPack владеет энкодером, `VideoCapture` наружу Surface не отдаёт). Отложено (не приоритет), выбран вариант «ничего не делать» — рабочая сборка без оверлея. Путь на будущее — **свой `SurfaceProcessor` как `CameraEffect`** (один `Preview`, мульти-выход энкодер+свой `SurfaceView`; публичный API с 1.3; `GlTee.kt` из истории — шаблон). Полный разбор, варианты и потери — [docs/camerax-overlay-plan.md](docs/camerax-overlay-plan.md). Попутно поднят тулчейн: CameraX 1.7.0-alpha02, AGP 9.2.0, Gradle 9.4.1, compileSdk 37 (коммит `c83a47e`).
