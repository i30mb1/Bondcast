# План: burn-in оверлеи (карта) поверх стрима на CameraX

> Статус на **2026-07-08**: **ОТЛОЖЕНО**. Захват на CameraX работает (встроенная камера + USB), эфир и превью живые. Burn-in оверлея (карта, впечатанная в видео для зрителей) **не реализован** — упёрлись в ограничение CameraX, разобрались в причине, выбрали путь на будущее. Оверлей не приоритетен, поэтому оставили рабочую сборку без него.
>
> Целевой путь, когда вернёмся: **свой `SurfaceProcessor` как `CameraEffect`** (см. «Решение»).

## Цель

Показывать местоположение (карту) **в самом видеопотоке**, чтобы её видели зрители (burn-in), а не только локально. StreamPack остаётся энкодером/муксером/SRT; захват уже переведён на CameraX (см. коммиты миграции). Нужен слой «рисовать поверх стрима» + первый плагин — карта (Yandex MapKit).

Модульная заготовка уже в репо и работает вхолостую: `:feature:overlay` (`StreamOverlay`, `OverlayCompositor`, `overlayCompositor()`, `DebugOverlay`), проброшена `AppGraph → StreamController → StreamPackEngine → CameraXVideoSourceFactory`. Компоновщик пуст → эффект не вешается → эфир как в рабочем Шаге 1.

## Что перепробовали (хронология, устройство A024, бэк srtla_rec 93.84.96.193:5000)

Пробовали штатный `androidx.camera.effects.OverlayEffect`, впечатывая оверлей в энкодерную Surface StreamPack:

1. **`OverlayEffect` всегда включён, два `Preview` (энкодер + дисплейный `CameraXViewfinder`).**
   → Эфир **рвётся**: `фаза → Live`, видео HEVC пару секунд идёт, затем шквал `EglImage dataspace changed, need recreate`, `Dropping frame going backward in time`, `System time diverged REALTIME vs UPTIME` → `StreamerPipeline: Video input is stopped` → `StreamController` реконнектит по кругу → на сервер ~205–212 кбит/с (только аудио). В логах `W/CameraUseCaseAdapter: Unused effects: [OverlayEffect]`.

2. **Гейт `if (compositor.hasOverlays())`** — пустой компоновщик не вешает эффект → регресс снят (эфир жив). Это текущее рабочее состояние.

3. **`DebugOverlay` (яркая плашка + счётчик кадров).** Оверлей **виден в экранном превью** (механизм отрисовки рабочий), но на бэк **ни картинки, ни оверлея**.

4. **`queueDepth 0→4` + только энкодерный `Preview` (без дисплейного).**
   → На бэк пошли **и картинка, и оверлей**. РАБОТАЕТ (но без экранного превью), поворот уехал на 90°.

5. **Вернули дисплейный `Preview` (два `Preview`) с `queueDepth=4`.** → Снова рвёт. `queueDepth` не при чём.

6. **H.264 вместо HEVC.** → Тоже пусто на бэк. Кодек не при чём.

7. **Апгрейд CameraX 1.6.0 → 1.7.0-alpha02** (потащил AGP 9.2.0, Gradle 9.4.1, compileSdk 37). → **Та же ошибка**: `Unused effects`, `EglImage dataspace changed`, ~205 кбит/с. 1.7 не лечит.

**Вывод экспериментов:** через штатный `OverlayEffect` **превью + эфир + оверлей одновременно недостижимо**. Ломает именно **второй `Preview`** одного таргета PREVIEW; `queueDepth`, кодек и версия CameraX ни при чём.

## Корневая причина (проверено грепом по исходникам 1.7.0-alpha02)

- **Встроенный `OverlayEffect` одно-выходной ПО ДИЗАЙНУ.** `camera-effects/.../internal/SurfaceProcessorImpl.java`: строка 58 «This implementation only expects one input surface and one output surface», строка 85 один `Pair<SurfaceOutput, Surface> mOutputSurfacePair`, строки 159–165 «Only one output Surface is allowed. Unregister the existing…» → второй `onOutputSurface()` **сносит первый**. Он физически не может кормить и энкодер, и превью.
- **Два `Preview` одного таргета + один эффект → CameraX включает StreamSharing** (общий GL-рендерер дублирует поток в оба выхода). MediaCodec-энкодерная ветка ожидает **видео-dataspace** (BT.601/709, limited range, SDR_VIDEO), дисплейная — экранный (sRGB, full). Общий dataspace-осведомлённый рендерер флипает dataspace каждый кадр → `EglImage dataspace changed, need recreate` (строка из native/JNI GL-слоя) → стойл → тайминг кадров плывёт → энкодер дропает → StreamPack объявляет «video input stopped».
- **Контраст:** внутренний `camera-core/.../processing/DefaultSurfaceProcessor.java` — мульти-выходной (`Map<SurfaceOutput, Surface> mOutputSurfaces` строка 92, пофреймовый цикл по всем выходам строка 230), но `@RestrictTo`/internal — не публичный API.
- **Правило команды CameraX** (тред camerax-developers): таргетить эффект в `PREVIEW` **или** `VIDEO_CAPTURE`, но **не оба** при шаринге.

## Планы Google / «ждать фикс»

- Эффект на `PREVIEW | VIDEO_CAPTURE` через общий поток **пофиксили в 1.5.1/1.6.0-alpha01** — но это **Preview + VideoCapture**, а `VideoCapture` пишет в свой `Recorder`/MP4 и наружу Surface не отдаёт → **нам недоступно** (энкодер у StreamPack).
- Наш кейс «**два `Preview` + эффект**» — не санкционированный паттерн; **тикета/плана фикса под него нет**. Санкционированный ответ Google — «используйте VideoCapture», чего мы не можем.
- **Вывод:** «просто дождаться фикса» под нашу архитектуру — слабая ставка (вероятно, бесконечно). Открытый смежный тикет: <https://issuetracker.google.com/issues/466122134> (Media3Effect/StreamSharing) — не про нашу поломку.

## Варианты (ранжир по риск-багов)

| Вар | Что | Оверлей | Риск багов | Кастомного кода |
|---|---|---|---|---|
| **A. Ничего не делать (текущее, ВЫБРАНО)** | 2 `Preview` (энкодер + `CameraXViewfinder`), без эффекта — работает | нет | минимальный | нет |
| **D. Ждать фикс Google** | = A + иногда смотреть трекер | нет (пока) | минимальный | нет |
| **C. Свой `SurfaceProcessor`** | 1 `Preview` + `CameraEffect(наш процессор)` → энкодер + свой `SurfaceView` | в эфире и превью | средний | один GL-компонент; свой дисплей-подгон; 8-бит |
| **B. Встроенный effect + свой превьювер** | 1 `Preview`+`OverlayEffect`→энкодер; дисплей через `ImageAnalysis`→свой `SurfaceView`+Compose-оверлей | в эфире; в превью отдельным слоем | выше | 2 потока камеры, рендер ImageAnalysis, оверлей дважды, чинить поворот |

B хуже C по багам (два потока + два рендера + дублирование оверлея), хотя интуитивно кажется легче.

## Решение

**Сейчас — вариант A** (оставляем рабочую сборку без оверлея). Оверлей не горит; ноль кастомного GL — минимум багов.

**Когда карта станет нужна — вариант C: свой `SurfaceProcessor` как `CameraEffect`.** Это «инструменты CameraX + не шарим поток»:

```
ОДИН Preview use case + CameraEffect(PREVIEW, executor, OverlaySurfaceProcessor)

OverlaySurfaceProcessor (наш GL, мульти-выход как DefaultSurfaceProcessor):
  onInputSurface(req)  → отдаём SurfaceTexture, камера пишет в неё
  onOutputSurface(out) → СОХРАНЯЕМ в список (НЕ перезатирать!):
       out = энкодерная Surface StreamPack (как сейчас, через SurfaceProvider)
     + доп. выход = свой дисплейный SurfaceView (свой EGLSurface внутри процессора)
  каждый кадр: камера + оверлей-текстура → блит во ВСЕ выходы (eglMakeCurrent по каждому)
  поворот: из SurfaceOutput transform-матрицы (НЕ canvas.rotate) → чинит 90°
```

Почему это работает: **один `Preview` → StreamSharing не включается** → нет `Unused effects`, нет dataspace-choke. `SurfaceProcessor`/`CameraEffect` — **публичный API с CameraX 1.3**. `GlTee.kt` из истории (commit `c594af3`, 267 строк, фан-аут камера→OES→N Surface с матрицей поворота) — готовый шаблон GL-фан-аута, его логику переносим внутрь процессора. Подтверждено практикой (тред camerax-developers: «additional native EGL context… draw to both surfaces via makeCurrent… stream and preview look great»).

### Что теряем, выбрав C (на будущее)
- **`CameraXViewfinder` для дисплея** — отдаём; fit/центр-кроп/поворот/зеркало и фолбэк SurfaceView↔TextureView решаем в GL сами (риск вернуть баг «портрет растянут»).
- **HDR 10-бит** — блит 8-битный; HDR-путь недоступен без 10-бит конвейера (сейчас всё равно SDR, HDR-стриминг = отдельный большой проект).
- **Свой GL** — новая поверхность багов (цвет/dataspace/тайминг/зеркало) и поддержки.

### Что НЕ теряем
- Все контролы камеры (зум/фокус/экспозиция/фонарик/переключение/ночной режим) — на стороне захвата, до процессора.
- Стабилизацию превью (включается на `Preview`).
- Правильный поворот — берём transform из `SurfaceOutput` (даже плюс).
- Публичность API; USB-путь (`UvcVideoSource`) не задет.

## Дальнейшие шаги (когда вернёмся к оверлею)

1. `OverlaySurfaceProcessor : SurfaceProcessor` — перенести GL-логику `GlTee`, добавить наложение оверлея (`Bitmap` из `OverlayCompositor.drawAll` → GL-текстура → блендинг), мульти-выход (список `SurfaceOutput`).
2. Повесить `CameraEffect(PREVIEW, executor, процессор)` на единственный `Preview` в `CameraXVideoSource`; энкодерная Surface — как сейчас через `SurfaceProvider`.
3. Дисплей — свой `SurfaceView` как второй выход процессора; поворот/fit из `SurfaceOutput`-матрицы.
4. Затем модульная система карт: `:feature:map:api` + `:feature:map:impl-yandex` (`MapProvider`, `LocationProvider`, `MapOverlay : StreamOverlay`), проводка провайдера в `AppGraph`.
5. Проверить регресс: переключение камер, фронталка (зеркало/поворот), USB ZV-E1, термо-смена битрейта.

## Тулчейн (поднят в ходе исследования, коммит `c83a47e`)

CameraX **1.7.0-alpha02**, AGP **9.2.0** (требует Gradle **9.4.1**), compileSdk **37** (Platform 37.0). Апгрейд собран и проверен на устройстве; runtime рабочий.
