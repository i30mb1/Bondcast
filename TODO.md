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
| Бондинг | **SRTLA** (протокол BELABOX): порт `srtla_send` через NDK, работает как локальный UDP-прокси: libsrt → 127.0.0.1 → srtla_send → N сетей → srtla_rec на сервере | совместимость со всей belabox-экосистемой из коробки. Привязка UDP-сокетов к сетям — `ConnectivityManager.requestNetwork` (cellular+wifi+ethernet) + `android_setsocknetwork()` из NDK multinetwork.h |
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

- [x] Gradle-скелет: AGP 9, version catalog, toolchain, R8 + proguard
- [x] Compose + Material3 тема, edge-to-edge
- [x] Android CLI 1.0 инициализирован (`android init`), скиллы agentов: `android-cli`, `camera1-to-camerax`, `testing-setup` → `~/.claude/skills`
- [x] Доделать переименование: пакет `n7.bondcast`, манифест на относительных `.MainActivity` / `.BondcastApp`
- [x] Закоммитить каркас
- [ ] GitHub Actions: сборка + тесты на PR

## Фаза 1 — MVP: камера → SRT на сервер

Критерий готовности: 1080p30 @ 4.5 Mbps H.264 + AAC летит час без падения на SRS (просмотр по HTTP-FLV), задержка ~2 с, стрим переживает сворачивание приложения.

- [x] Разрешения: CAMERA, RECORD_AUDIO, POST_NOTIFICATIONS (экран-заглушка при отказе)
- [x] Полноэкранное превью камеры на CameraX (landscape-first)
- [x] Спайк: видеоисточник CameraX для StreamPack (кастомный source, API v3); если связка хрупкая — свой GL-фан-аут (экран + surface энкодера), от StreamPack остаются муксер/эндпоинт
- [x] `StreamEngine` — свой интерфейс поверх StreamPack: `prepare / startStream / awaitDisconnect / stopStream / bindPreview / readStats`
- [x] Кодирование H.264 + AAC → MPEG-TS → SRT caller (streamid, passphrase, latency настраиваемые)
- [x] Экран настроек: адрес `srt://<IP>:10080`, streamid `#!::r=live/<name>,m=publish`, passphrase, разрешение / fps / битрейт / latency (по умолчанию 1500 — как на сервере) (DataStore)
- [x] Foreground service + нотификация с кнопкой Stop; keep screen on
- [x] HUD поверх превью: статус соединения, таймер, текущий битрейт, RTT, потерянные пакеты (SRT-статистика, сэмпл раз в секунду)
- [x] Реконнект с экспоненциальным backoff (переживает пропажу сети на 30 с)
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

## Фаза 3 — SRTLA-бондинг (флагманская фича)

- [ ] NDK-порт `srtla_send` (CMake, JNI-управление, работает потоком внутри процесса)
- [ ] Захват сетей: `requestNetwork(CELLULAR)` + `(WIFI)` + `(ETHERNET/USB)`, держим cellular живым при активном WiFi
- [ ] Привязка UDP-сокетов srtla к конкретным сетям (`android_setsocknetwork`)
- [ ] UI линков: per-link throughput / RTT / loss, вкл-выкл линка, приоритеты
- [ ] Бондированный спидтест перед стримом → рекомендация безопасного битрейта
- [ ] ABR v2 с учётом суммарной полосы всех линков (belabox-подобный алгоритм)
- [ ] e2e-стенд: docker `srtla_rec` перед SRS (сам SRS srtla не понимает), тест деградации (отключаем WiFi посреди стрима — картинка не падает)
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
- [ ] Оверлеи: часы, GPS-скорость/карта, пульс (BLE-датчик), текст — вшиваются в картинку
- [ ] Replay-буфер: сохранить последние N секунд кнопкой
- [ ] Remote control: веб-дашборд для «режиссёра» (статы, смена битрейта/сцены удалённо)
- [ ] Управление командами чата (модеры могут включить BRB и т.п.)
- [ ] Wear OS: старт/стоп, статы, privacy-кнопка с руки

## Фаза 6 — экосистема

- [ ] **Bondlink**: второй телефон как дополнительный канал бондинга (аналог Moblink)
- [ ] Внешние камеры: UVC (USB), DJI/GoPro по WiFi
- [ ] Свой облачный relay: srtla_rec + рестрим на Twitch/YT/Kick + DVR + серверные оверлеи (монетизация)
- [ ] Simulcast на несколько площадок (через свой relay)
- [ ] Интеграция с noalbs / IRLToolkit (авто-переключение сцен по health)

## Бэклог / идеи без фазы

- [ ] Следить за srtla2 и RIST-бондингом — добавить, когда стабилизируются
- [ ] AV1 hw encode на новых SoC
- [ ] Своя Kotlin-реализация srtla-клиента в `:kotlin` (кастомный шедулинг линков, полные unit-тесты) вместо NDK-порта
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

- **2026-07-03**: StreamPack + srtdroid вместо своего пайплайна — скорость MVP; изолируем за `StreamEngine`, чтобы можно было заменить. Бондинг — SRTLA (совместимость с belabox-экосистемой), реализация NDK-портом `srtla_send`; своя Kotlin-реализация — возможная v2. SRT socket groups отклонены (experimental, нет серверов).
- **2026-07-03**: Дев/прод-сервер — **SRS v6** с готовым конфигом из `D:\AndroidProject\docs\stream` (вместо MediaMTX); для бондинга `srtla_rec` встанет перед SRS. Камера — **CameraX** (решение владельца); StreamPack остаётся слоем энкодер/муксер/SRT, интеграция через кастомный видеоисточник проверяется спайком. Исследование Android — через Android CLI 1.0 (`android` + агентские скиллы) и adb/gradlew, не через Studio.
