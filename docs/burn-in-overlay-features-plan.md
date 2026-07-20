# Burn-in оверлеи: план фич после фикса CameraX

## 1. Контекст и что разблокировал фикс

Google принял CameraX CL Gerrit 4169526 — «Fix StreamSharing for multiple Preview use cases sharing an effect». До него связка «два `Preview` use case (энкодерный + дисплейный) + общий `OverlayEffect`» ломала StreamSharing: `isStreamSharingChildrenCombinationValid` отбраковывал комбинацию из двух PREVIEW-детей, StreamSharing разваливался в `null`, эффект вешался напрямую на оба потока, и одно-выходной `SurfaceProcessorImpl` сносил первый выход. Практический симптом — энкодер отдавал ~205 кбит/с (фактически только аудио). Фикс разрешает несколько Preview-детей в StreamSharing, и многовыходной `DefaultSurfaceProcessor` кормит эффектом ОБА выхода. Релиз-нот: «Fixed an issue where binding multiple Preview use cases with an OverlayEffect caused one Preview stream delivery failure.»

Следствие для нас: штатный `OverlayEffect` теперь работает одновременно с превью, эфиром и оверлеем. Значит **вариант C из `docs/camerax-overlay-plan.md` (свой GL `SurfaceProcessor`) отменяется** — самодельный процессор больше не нужен, burn-in делаем на штатном эффекте.

Заготовка `:feature:overlay` уже собрана под штатный эффект. В репо есть контракт: `interface StreamOverlay { fun draw(canvas: Canvas, frame: Size) }`, `OverlayCompositor { register/unregister/hasOverlays/drawAll(canvas, frame) }` + фабрика `overlayCompositor()`. `drawAll` вызывается из `OverlayEffect.onDrawListener` покадрово на выделенном HandlerThread «camerax-overlay», `frame` = размер энкодерного кадра. Эффект прикрепляется только когда `hasOverlays()` (см. `CameraXVideoSource.kt:156`), сам `OverlayEffect` создаётся лениво (`CameraXVideoSource.kt:225-243`, `queueDepth=4`).

## 2. Enablement — что включить, чтобы оверлеи ожили

Кратко и по файлам. Пункты 2–5 — блокеры до массового плодения фич.

0. **Получить CameraX с CL 4169526.** ⚠ СТАТУС (2026-07-20): фикс **вмёржен в Gerrit, но НЕ опубликован** ни в одном релизе `androidx.camera:*`. Версию бампнуть пока не во что. Три варианта моста, пока не вышел стабильный/alpha:
   - **A. Ждать публикации.** Отслеживать релиз-ноты CameraX; camera-core alpha выходят ~раз в 2 недели. Когда CL попадёт в alpha — бампнуть version-каталог, пересобрать `:feature:camera`. Ноль работы сейчас; весь on-device-план ждёт каденса Google. Меж тем делать fix-independent часть (§3 каркас, классы оверлеев, снимки данных — всё, кроме on-device risk-gate эффекта).
   - **B. androidx.dev snapshot (рекомендуется, если двигаемся сейчас).** androidx публикует per-commit SNAPSHOT-сборки. Найти buildId, содержащий мёрж 4169526, добавить maven-репо `https://androidx.dev/snapshots/builds/<buildId>/artifacts/repository` и запинить `camera-*:<snapshot-version>`. Даёт РОВНО вмёрженный код, проверяем на устройстве немедленно; конфиг выкидной — вернуть версию, когда выйдет alpha. Легче, чем сборка из исходников.
   - **C. Воскресить вариант C (свой GL `SurfaceProcessor`).** НЕ зависит от Google: один `Preview` + `CameraEffect(наш процессор)` → StreamSharing не включается, баг не применим (см. `docs/camerax-overlay-plan.md`). Работает сегодня, но это тот самый кастомный GL, которого хотели избежать (8-бит, свой дисплей-подгон) → станет мёртвым грузом, если фикс скоро выйдет. Брать, только если публикация далеко.
1. **Гейт `hasOverlays()`.** Оставить как есть (`CameraXVideoSource.kt:156`): эффект и его пер-кадровый оверхед подключаются только при наличии хотя бы одного зарегистрированного оверлея. Ничего не менять — это правильная экономия.
2. **Двойной поворот 90°.** Сейчас `targetRotation` НЕ ставится ни на `encoderPreview` (`CameraXVideoSource.kt:138`), ни на `displayPreview` (`:145`); поворотом владеет только StreamPack через `CameraXSourceInfoProvider.rotationDegrees = SENSOR_ORIENTATION` (`CameraXSourceInfoProvider.kt:18-19`). Инвариант: **поворот живёт в ОДНОМ месте (StreamPack)**. Двойного поворота сегодня нет; он появится, если кто-то поставит `targetRotation` на превью, НЕ обнулив `rotationDegrees`. Каркас (см. §3) лишь компенсирует поворот на канве для рисования, не трогая `rotationDegrees`.
3. **Зеркало фронталки.** `isMirror=true` для фронт-линзы (`CameraXSourceInfoProvider.kt:21-22`) применяется StreamPack ко ВСЕМУ кадру ПОСЛЕ burn-in. Значит любой текст оверлея на селфи-камере будет зеркально перевёрнут. В `draw` приходит только `Size` — ни поворот, ни `isMirror` в оверлей не прокидываются. Требуется прокинуть их в оверлей и контр-зеркалить на канве (см. §3, шаг смены контракта).
4. **On-device risk-gate на A024.** Пункты 2–3 непроверяемы «на бумаге»: собрать ОДИН текстовый оверлей и эмпирически выверить читаемость на фронт+портрет+ландшафт (и тыл). Порядок композиции контр-матрицы (rotate→mirror vs mirror→rotate) обязан совпасть с конвейером StreamPack — иначе текст всё равно перевёрнут. Это риск №1 на устройстве, до него фичи не плодить.
5. **Правило перф: `draw()` без аллокаций.** `drawAll` идёт покадрово (30/60 fps) на HandlerThread, COW-итерация синхронна. В `draw` запрещены: `format`/`parse`, боксинг, декод `Bitmap`, сборка `StaticLayout`, `collect`/Flow, `new Matrix`. Всё это готовится вне кадра (см. §3).

## 3. Общий каркас оверлеев

> **Статус 2026-07-20: fix-independent каркас РЕАЛИЗОВАН, собирается зелёно** (`:app:assembleDebug` ок, 4 юнит-теста `OverlayLayoutTest` зелёные). Сделано: смена контракта (`OverlayFrame` с `size`/`rotationDegrees`/`isMirror`/`uprightSize`; `StreamOverlay.draw(OverlayFrame)`; `OverlayCompositor.register(overlay, z)` + `drawAll(OverlayFrame)`), z-порядок (`OverlayCompositorImpl` — `@Volatile`-массив, lock-free `drawAll`), декоратор нормализации (`OverlayCompositorWithOrientation` с кэш-матрицей), `Anchor`+`OverlayLayout` (чистая математика, покрыта тестом), обновлены call-site `CameraXVideoSource` (прокидывает rotation/mirror из InfoProvider) и `DebugOverlay`. ⚠ НЕ проверено на устройстве (нет артефакта с фиксом): **точный порядок композиции контр-матрицы rotate↔mirror** — финализировать в §2.4 risk-gate. Отложено до первого реального оверлея: базовый `SnapshotOverlay` (снимок данных + lifecycle корутины) — его API валидируем на живом потребителе (напр. T1.3 бонд-полоса), а не угадываем.

Единожды пишем инфраструктуру, чтобы каждый `draw()` был тонким. **Важно: §3 — это не «хост поверх Impl», а смена контракта. Существующий `register(overlay)` не имеет z-параметра, а `draw(canvas, frame: Size)` не отдаёт поворот/зеркало.** Поэтому первая задача каркаса — расширить интерфейс, иначе все фичи компилируются против несуществующей сигнатуры.

**Шаг 0 (смена контракта).** Расширить:
- `StreamOverlay.draw(frame: OverlayFrame)` вместо `draw(canvas, Size)`;
- `OverlayCompositor.register(overlay, z: Int)` — с приоритетом слоя.

```
data class OverlayFrame(
  val canvas: Canvas,
  val size: Size,            // энкодерный кадр (буфер, сенсор-ориентация)
  val uprightSize: Size,     // после нормализации поворота (W/H своп при 90/270)
  val rotationDegrees: Int,  // SENSOR_ORIENTATION
  val isMirror: Boolean,     // true для фронт-линзы
)
```

**Нормализация ориентации (закрывает §2.2–2.3 разом).** Хост (декоратор поверх `OverlayCompositorImpl`) перед вызовом фич приводит канву к upright-немиррор пространству: `canvas.save()` → применить закэшированную контр-матрицу (rotate −rotationDegrees вокруг центра; при `isMirror` — `scale(-1,1)`), вызвать фичи, `canvas.restore()`. StreamPack всё равно повернёт/зеркалит финал — оверлей вернётся в правильную ориентацию для зрителя. Инвариант: **rotation остаётся в StreamPack; каркас только компенсирует, не меняя `rotationDegrees`.** Порядок rotate/mirror выверить на устройстве (§2.4).

**Перф контр-матрицы.** `Matrix` кэшировать в хосте и пересчитывать ТОЛЬКО при смене `size`/`rotationDegrees`/`isMirror`; в кадре — `canvas.concat(cached)` / `setMatrix`, `save/restore` без аллокаций.

**Якоря и safe-zone.**
```
enum class Anchor { TOP_START, TOP_CENTER, TOP_END, MID_START, CENTER, MID_END, BOTTOM_START, BOTTOM_CENTER, BOTTOM_END }
```
`OverlayLayout(frame, anchor, marginDp, safeAreaPercent=0.05)` → базовая точка в px. Title-safe 5%-инсет от краёв (платформы кропают). Масштаб: `scale = uprightSize.height / 1080f`; размеры задаём в «дизайн-px под 1080p» и умножаем на `scale` — единый вид на 720p/1080p/4K.

**Анти-аллокация (жёсткий инвариант каждого `draw`).** Базовый `AbstractOverlay`:
- кэширует `Paint`/`Path`/`RectF`/`Rect textBounds`/`StringBuilder` в `init`, никогда в `draw`;
- данные — через `@Volatile var snapshot: T`, обновляемый фон-корутиной (`scope.launch { source.collect { snapshot = it.toRenderModel() } }`); `draw` делает одно volatile-чтение и рисует;
- числа→строки форматируются в снимке (вне `draw`);
- `Bitmap` (лого/эмоуты/аватары/бейджи/тайлы) декодятся один раз в фоне (Coil/BitmapFactory), в снимок кладётся готовый `Bitmap`; `draw` только `drawBitmap`.

**Z-порядок.** COW-список сортируется по `z`. Слои: подложки (10) < HUD-текст (20) < чат (30) < алерты (40) < LIVE-бейдж/водяной знак (50); BRB — выше всех (непрозрачное перекрытие).

**Тумблер и панель.** Каждая фича регистрируется/снимается через `LaunchedEffect(enabled){ overlayCompositor().register(...) }` (идиома chat). Единый `PANEL_OVERLAY` в рейле `StreamScreen` (независимый boolean, как chat) со списком тумблеров и общими контролами (позиция/opacity/scale) на фичу. Каждое поле настройки — поле в `StreamSettings` (с дефолтом) + три точки в `SettingsRepository` (key/read/write).

## 4. Каталог фич по тирам

Пометки: `★` — источник уже течёт; `⚠НОВЫЙ` — новый модуль; `⚠РАСШ` — расширение существующего источника.

### TIER 1 — данные уже текут (ноль нового источника)

| Фича | Что рисует | Источник | Модуль | Effort | Risk |
|---|---|---|---|---|---|
| HUD битрейт/RTT/loss/health | `4200 kbps · RTT 74 · loss 0/s · buf 210ms` + кружок общего здоровья | ★ `videoBitrateKbps`/`stats.sendRateKbps`, `stats.rttMs`+`health.rttLevel`, `health.lossPerSec`+`lossLevel`, `stats.sndBufferMs`+`health.bufLevel`, `health.overall` | overlay | M | null-гейт вне Live (`stats`/`health`=null); HealthLevel→ARGB вне draw; семпл 1000мс |
| Пер-линк бонд-полоса | сегменты по линкам, ширина=доля трафика, `#0 LTE 1800k`, цвет по `reg`/`transport` | ★ `StreamController.links: List<LinkInfo>` (`id`,`transport`,`reg`,`sendRateKbps`,`inFlight`,`window`); доля=`sendRateKbps/sumOf{sendRateKbps}.coerceAtLeast(1)` | overlay | M | пустой список при бондинге off → скрывать; **RTT/loss на линк НЕТ** (см. N3); строки/цвета вне draw |
| LIVE / Reconnecting-бейдж | пульс-точка + `LIVE` / жёлтое `RECONNECTING (попытка N)` / `CONNECTING` | ★ `phase: StreamPhase` (`Live`/`Connecting`/`Retrying(attempt,cause)`/`Idle`) | overlay | S | пульс по времени, не по кадрам; на `Idle` не рисовать |
| Часы + аптайм | `HH:mm:ss` и/или `LIVE 01:23:45` | ★ `System.currentTimeMillis()`; аптайм=`now − (phase as Live).sinceEpochMs` | overlay | S | тик 250мс в фоне, не по кадрам (мельтешение) |
| Термо-огонёк | огонёк по `heat`(0..1) + `statusLabel` + опц. `°C` | ★ `ThermalMonitor.states()`: `heat`,`statusLabel`,`batteryTempC` | overlay | S | сгладить `heat` (EMA) от дребезга; опц. авто-скрытие при «норма» |
| Имя/тип камеры | бейдж `FRONT`/`BACK`/`USB` | ★ `currentCamera: CameraOption?` (`.label`) | overlay | S | `label` USB может быть просто «USB» — не обещать модель; зеркало решает каркас |
| «Урезано ABR» | стрелка «битрейт срезан ABR» | ★ `abrEnabled && videoBitrateKbps < maxBitrateKbps` | overlay | S | гистерезис, чтобы не мигать на границе |
| Энкодер-оверлоад | варн «энкодер не тянет» | ★ `stats.encoderLagMs` + `health.encoderLevel` | overlay | S | порог + гистерезис; null-гейт вне Live |
| «Урезано термо» | бейдж «превью/битрейт душит система» | ★ `ThermalMitigations.previewEnabled`/`bitrateCapFraction` | overlay | S | показывать только при активном кэпе |
| OBS-сцена / статус | бейдж текущей сцены + статус стрима OBS | ★ `ObsController.currentScene` + `streamStatus` | overlay | S | показывать только при `obsEnabled`; строка вне draw |
| Лого / водяной знак | PNG в углу с opacity | локальный файл/URI из настроек | overlay | S | масштабировать при загрузке до целевого px, не в draw; z=50 |
| Статич. текст / marquee | текст стримера (`!socials`, донат-цель), опц. бегущая строка | `overlayText` из настроек; marquee-offset тикает в фоне | overlay | S | кэшировать `StaticLayout`; эллипсис длинного текста |
| BRB-карточка | крупная непрозрачная подложка «Скоро вернусь» + опц. часы | **⚠ нужен рантайм-стейт** (мини-`BrbController: StateFlow<Boolean>`); текст из настроек | overlay | M | отдельная кнопка в рейле (не в панели) для мгновенного BRB; z высокий, рисовать непрозрачно; аудио продолжает идти |
| Loss-sparkline (N1) | мини-график `sendRateKbps`/`lossPerSec` за 60с | ★ кольцевой буфер 60 точек в фоне | overlay | M | `Path` пересобирать в фоне (раз в 1000мс), не в draw |
| Авто-BRB по здоровью (N2) | ненавязчивый баннер «Восстанавливаю связь…» | ★ `health.overall==BAD && phase==Retrying` дольше T | overlay | S/M | гистерезис вне draw, не мигать на кратких просадках |

### TIER 2 — переиспользуем `:feature:chat`

| Фича | Что рисует | Источник | Модуль | Effort | Risk |
|---|---|---|---|---|---|
| Burn-in чата | последние N сообщений: `[значок] Ник: текст` с эмоутами, цвет ника | ★ `ChatController.messages: List<ChatEvent.Message>` (модерация применена); рендер по `fragments`, `author.color`, `author.primaryRole`/`author.badges[].imageUrl` | overlay → `chat:domain` | L | самый тяжёлый draw: строгий кэш `StaticLayout`+`Bitmap`; эмоуты/аватары async с плейсхолдером; N=3–6; пересборка layout только при смене списка; z=30; **приватность: тумблер «скрывать ники»** |
| Алерты (донат/фоллоу/рейд/подписка/награда) | тост `User задонатил 500₽!` / `Raid 120` с анимацией и очередью | ⚠РАСШ: `ChatEvent.Rich` наружу НЕ выведен — `messages` отдаёт только `Message`. **Добавить `ChatController.richEvents: SharedFlow<ChatEvent.Rich>`** (replay=0). Поля: `Donation(userName,amountMinor,currency,tier,message)`,`Membership`,`Follow`,`Raid`,`Reward` | overlay → `chat:domain` (правка домена, не новый модуль) | M (после расширения) | форматирование суммы (`amountMinor`+`currency`) вне draw; очередь с границей (дропать старые); анимация по времени; z=40; **приватность: анонимизация имён донатеров — дефолт ON** |

Примечание: T2.2 — это **расширение источника**, а не «reuse готового», в отличие от T2.1. Не смешивать в одну задачу.

### TIER 3 — нужен новый источник данных

| Фича | Что рисует | Источник | Модуль | Effort | Risk |
|---|---|---|---|---|---|
| GPS-скорость/высота/курс | `54 км/ч · 180 м · ЮВ` | ⚠НОВЫЙ `:feature:location` (domain/impl): `FusedLocationProviderClient`, `PRIORITY_HIGH_ACCURACY ~1с` → `LocationState(speedMps,altitudeM,bearingDeg,accuracyM)` StateFlow; фабрика `locationProvider(context)` + `WithMutex`/`WithLogging` | overlay → `location:domain` | M + модуль | `ACCESS_FINE_LOCATION` (рантайм + FGS-тип location, version-гейт minSdk 29); **приватность: скорость у дома = деанон → OFF по умолчанию + экран-предупреждение**; батарея; EMA скорости вне draw |
| Мини-карта | карта в углу с маркером/треком | ⚠НОВЫЙ `:feature:map` (api + impl-tiles): статик-тайлы (Mapbox Static / OSM) → `MapTileSource.tileFor(lat,lon,zoom): Bitmap` (MapKit рисует в свою Surface — не подходит для Canvas-burn-in) + позиция из `:feature:location` | overlay → `map`+`location` | L + модуль | сеть только в фоне+LRU-кэш, НЕ в draw; квота/ключ API; **приватность: опция офсета/скрытия домашней зоны**; z=10 |
| BLE-пульс | `♥ 142` с пульсацией в такт | ⚠НОВЫЙ `:feature:heartrate` (domain/impl): GATT HRS `0x180D`, характеристика `0x2A37` (notify) → `HeartRateState(bpm,sensorContact,timestampMs)` StateFlow; фабрика `heartRateMonitor(context)` | overlay → `heartrate:domain` | L + модуль | `BLUETOOTH_SCAN/CONNECT` (Android 12+, version-гейт minSdk 29); реконнект при разрыве; парс флага `0x2A37` (uint8 vs uint16); батарея |
| Компас | лента-компас / стрелка N | ⚠НОВЫЙ (лёгкий): `SensorManager.TYPE_ROTATION_VECTOR` → azimuth; `CompassProvider(context): StateFlow<Float>` прямо в `:feature:overlay` (или `:feature:sensors`) | overlay | M | low-pass фильтр от дребезга вне draw; калибровка магнитометра; учесть ландшафтную ориентацию телефона |
| Now Playing (N4) | текущий трек | ⚠НОВЫЙ (лёгкий): `MediaSessionManager`/`MediaController`-listener | overlay | S | лёгкий, без модуля |

### N3 (расширение источника, не оверлей)

RTT/loss на линк сейчас НЕ публикуются (`publishSnapshot` в `LinkIoLoop.kt:242`). Добавить поля в `LinkInfo` + геттеры в scheduler → пер-линк RTT/loss-цвет для бонд-полосы (T1.3). ⚠РАСШ, не новый модуль. Risk: трогаем горячий io-путь — считать дельты дёшево, не аллоцировать в снапшоте (инвариант zero-copy).

## 5. Рекомендованная последовательность

Критерий «первой фичи» — максимум IRL-ценности при нуле нового источника и минимальном риске. После enablement (§2) порядок:

1. **Каркас §3 целиком, начиная со смены контракта** (`OverlayFrame` + `register(overlay,z)` + нормализация ориентации + анти-аллокация). Без него все фичи не компилируются и текст рисуется боком/зеркально. Сразу же — on-device risk-gate (§2.4) на одном текстовом оверлее.
2. **Ядро «BELABOX в кармане»** на ★-источниках: пер-линк бонд-полоса (T1.3) + HUD битрейт/RTT/loss/health (T1.2) + LIVE/Reconnecting-бейдж (T1.8). Это то, ради чего продукт существует — стример на ходу видит деградацию сети до дисконнекта, зритель видит статус эфира.
3. **Дешёвые ★-бейджи**: часы+аптайм (T1.1), термо-огонёк (T1.4), имя камеры (T1.5), OBS-сцена/статус, «урезано ABR/термо», энкодер-оверлоад. Все — Effort S, источник готов.
4. **Брендинг/утиль**: лого (T1.6), статич. текст/marquee (T1.7), BRB (+ мини-`BrbController`), sparkline (N1), авто-BRB (N2).
5. **Чат**: T2.1 (burn-in чата) — тяжёлый draw, но источник готов.
6. **Расширить `ChatController.richEvents`** → T2.2 (алерты).
7. **Tier 3 по спросу**: `:feature:location` → GPS (T3.1) → карта (T3.2); BLE-пульс (T3.3); компас (T3.4). Порядок — по востребованности вертикали (вело/мото → GPS; фитнес → пульс).
8. Параллельно, как отдельная инвестиция в источник: **N3** (RTT/loss на линк) — обогащает уже сделанную T1.3.

## 6. Открытые вопросы / риски

- **Публикация CameraX с фиксом (блокер).** ⚠ CL 4169526 вмёржен в Gerrit, но пока НЕ в опубликованном артефакте `androidx.camera:*` — бампнуть версию не во что. Мост: ждать alpha / взять androidx.dev snapshot / воскресить вариант C (§2.0 A/B/C). До получения кода on-device-план не проверяем.
- **Зеркало + поворот (риск №1 на устройстве).** Порядок композиции контр-матрицы (rotate→mirror vs mirror→rotate) должен точно совпасть с конвейером StreamPack. Непроверяем на бумаге — обязателен эмпирический тест фронт+тыл × портрет+ландшафт (§2.4). Инвариант «поворот в одном месте (StreamPack), каркас компенсирует» — держать строго.
- **Перф на GL/HandlerThread.** `drawAll` покадрово (30/60 fps), COW-итерация синхронна. Любая аллокация/`format`/`Bitmap`/`StaticLayout`/`new Matrix` в `draw` = дропы кадров энкодера. Контр-матрицу и все снимки готовить вне кадра; ревьюить каждый `draw()` на ноль аллокаций.
- **Тест на устройстве A024.** Повторить on-device risk-gate после подъёма версии CameraX: убедиться, что энкодер снова отдаёт полный битрейт (а не ~205 кбит/с аудио-only) при активном оверлее на обоих превью.
- **Приватность GPS/карта.** Скорость у дома + мини-карта в вечном VOD = точная точка проживания. Дефолт OFF, экран-предупреждение при включении, для карты — офсет/скрытие домашней зоны. FGS-тип location + рантайм-permission (version-гейт minSdk 29).
- **Приватность имён в VOD.** Ники зрителей (T2.1) и имена донатеров (T2.2) впекаются несмываемо. Анонимизация донатеров — дефолт ON; для чата — тумблер «скрывать ники».
- **Батарея.** 1 Гц GPS, BLE-notify, тайл-загрузки карты — умеренный, но постоянный расход поверх стрима. Все периодические источники — с разумным интервалом и сглаживанием в фоне.
- **Расширения источников.** `richEvents` (T2.2) и RTT/loss на линк (N3) трогают домен чата и горячий io-путь бондинга соответственно — держать инвариант zero-copy, не аллоцировать в снапшотах.
