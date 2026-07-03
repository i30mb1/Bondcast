# План: SRTLA-бондинг (Фаза 3 + Bondlink)

> Прогресс: **M0 ✅** (протокол+шедулер в `:kotlin`, 20 тестов, сверено с C) · **M1 ✅**
> (локальный relay, 1 линк, проводка в сессию, UI бондинга) · M2–M5 — впереди.
> Серверная часть для e2e — `docs/srtla-rec.md`.

## Context

Bondcast сейчас шлёт SRT по **одному** аплинку (StreamPack → srtdroid → сервер). Нужны **два уровня бондинга**:
1. **On-device** — агрегировать собственные сети телефона (сотовая + Wi-Fi + USB/Ethernet) в один SRT-поток.
2. **Bondlink** — использовать **соседние устройства** как дополнительные аплинки (аналог BELABOX/Moblink): соседний телефон отдаёт свою сотовую сеть как ещё один линк.

SRTLA — протокол агрегации BELABOX: локальный UDP-прокси `srtla_send` принимает SRT-пакеты от локального SRT-caller'а (`127.0.0.1`) и раскидывает их по N линкам на `srtla_rec`, который собирает их обратно и отдаёт в обычный SRT-сервер (SRS). srtla прозрачен для SRT — хендшейк/streamid/passphrase идут end-to-end.

**Решение:** своя **Kotlin-реализация srtla** в модуле `:kotlin`, без NDK. Главный аргумент за NDK — привязка UDP-сокета к сети через `android_setsocknetwork()` — отпал: публичный `Network.bindSocket(DatagramSocket)` (API 22) делает это из Kotlin. Протокол небольшой и полностью юнит-тестируемый; relay соседних устройств — всё равно кастомная логика, которую проще держать в общем Kotlin-коде.

## Ключевая архитектура

**Граница модулей** (чистая логика отдельно от IO — под юнит-тесты):
- `:kotlin` — **чистый, детерминированный, без Android/IO/часов**. Кодек пакетов, инспектор SRT, шедулер как редьюсер `onEvent(event, nowNanos) -> List<action>`. Время инжектится (monotonic nanos).
- `:android` — сокеты, потоки, `ConnectivityManager`, foreground-сервис, UI, relay.

**Поток данных (streamer):**
```
StreamPack SRT-caller → 127.0.0.1:<localPort> (LocalRelaySocket)
   → SrtlaScheduler (выбор линка) → N BondingLink-сокетов (каждый Network.bindSocket)
   → srtla_rec → SRS
обратно: линки → снуп SRT ACK/NAK + SRTLA ACK → SendToLocal → SRT-caller
```

**Пакеты SRTLA** (первые 2 байта BE, сверено с BELABOX/srtla):
`KEEPALIVE=0x9000, ACK=0x9100, REG1=0x9200, REG2=0x9201, REG3=0x9202, REG_ERR=0x9210, REG_NGP=0x9211, REG_NAK=0x9212`. SRT control=0x8xxx (ACK=0x8002 last-ack@off16, NAK=0x8003), SRT data = старший бит первого uint32 = 0.
Регистрация: id 256 байт; conn0: REG1(random)→REG2(128 sender+128 rec)→REG2(full)→REG3; каждый доп.линк: REG2(full)→REG3. Окно: `score=window/(inFlight+1)`, WINDOW MIN/DEF/MAX=1/20/60 ×1000, INCR=30, DECR=100. Keepalive при простое 1с, линк мёртв 4с.

**Модель потоков:** один поток с NIO `Selector` на N+1 `DatagramChannel`, пул MTU-буферов, single-writer над `LinkState` (без локов на горячем пути). Колбэки `NetworkCallback` (binder-поток) кладут `LinkAvailable/LinkLost` в concurrent-очередь, разгребаемую в начале каждой итерации select.

**Интерфейс-композиция** (по конвенции проекта): `SrtlaClient` → `SrtlaClientImpl` → `SrtlaClientWithMutex` → `SrtlaClientWithLogging` + аксессор.

## Пакеты/файлы

`:kotlin` `kotlin/src/main/kotlin/n7/srtla/` — **готово (M0):**
- `protocol/` — `PacketType`, `SrtlaCodec`, `SrtInspector` (классификация SRT, seqnum wrap-safe), `GroupId`, `Bytes`.
- `scheduler/` — `SrtlaScheduler` (чистый редьюсер), `LinkState`, `WindowParams`, `Transport`, `SchedulerEvent`/`SchedulerAction`.

`:android` `android/src/main/kotlin/n7/bondcast/bonding/`:
- **M1 ✅:** `SrtlaClient`(+Impl/WithMutex/WithLogging), `SrtlaTarget`, `io/LinkIoLoop`.
- M2: `net/` — `NetworkProvider` + `ConnectivityNetworkProvider` (requestNetwork + bindSocket), `BondingLink`.
- M3: `BondingStats`.
- M4: `relay/` — `RelayForwarder`, `RelayNatTable`, `discovery/NsdDiscovery`, `PairingChannel`, `RelayRole`.

Правки существующего — **сделано в M0/M1:** `kotlin/build.gradle.kts` (test-deps), `android/build.gradle.kts` (`project(":kotlin")`), `StreamController` (инжект srtlaClient; srtla стартует до engine, `host=127.0.0.1:localPort`, не рвётся между SRT-реконнектами, stop в finally), `AppGraph`, `StreamSettings`/`SettingsRepository` (`bondingEnabled`/`srtlaHost`/`srtlaPort`), `SettingsScreen` + `DiscordUi` (`DiscordSwitchRow`, секция «Бондинг»), `AndroidManifest` (`CHANGE/ACCESS_NETWORK_STATE`).
Впереди: `StreamScreen`/`HudStats` — по строке на линк (M3); manifest `NEARBY_WIFI_DEVICES` + relay-сервис (M4).

## Bondlink relay (M4)

Relay **прозрачный** → вся srtla-логика переиспользуется (регистрация и SRT идут end-to-end).
- **RELAY-режим:** UDP-форвардер, интернет-сокет привязан к **сотовой** сети соседа, LAN-сокет смотрит на streamer; `RelayNatTable` (ключ — адрес streamer, TTL ~4с) роутит ответы обратно. Медиа не парсит.
- **STREAMER:** relay = обычный `BondingLink`, сокет привязан к **Wi-Fi/LAN**, назначение — LAN-адрес relay. Тег `transport=RELAY` → в HUD и меньший приоритет; при активном relay поднять `latencyMs`.
- **Discovery/pairing:** Android NSD (`_bondcast-relay._udp`) + числовой код сопряжения, ручной IP как fallback. Контрол-канал: сопряжение, порт, здоровье relay (сотовый RTT/throughput соседа), keepalive.

## Этапы

- **M0 ✅** — чистый кодек + шедулер + тесты (без устройства). `./gradlew kotlin:test`.
- **M1 ✅** — один линк, passthrough. Локальный relay поднят до `engine.startStream`, `StreamSettings`→127.0.0.1. **e2e (реальный srtla_rec) ещё не прогонялся.**
- **M2** — мульти-сеть on-device + failover. `ConnectivityNetworkProvider` (requestNetwork CELLULAR/WIFI/ETHERNET), динамика `LinkAvailable/Lost`, `network.bindSocket`. Verify: выключить Wi-Fi в эфире → сотовая тянет.
- **M3** — HUD по линкам + настройки. `BondingStats`→строки в `StreamScreen`; вкл/выкл + приоритет.
- **M4** — Bondlink relay. relay-режим + форвардер + NAT + NSD + pairing. Verify: два телефона.
- **M5** — ABR v2 + бондированный спидтест. Битрейт от суммарной полосы всех линков.

## Серверная часть (предусловие для M1+ e2e)

SRS не понимает srtla → перед ним нужен **srtla_rec**. Деплой — `docs/srtla-rec.md`.

## Verification

- Юнит-тесты (`:kotlin`) — кодек и шедулер без устройства (основная гарантия протокола).
- Реальный сервер — srtla_rec перед SRS, просмотр HTTP-FLV.
- Failover на устройстве — выключение Wi-Fi/сотовой в эфире.
- Два устройства — relay (M4).
- Сборка: `./gradlew kotlin:test --q` и `./gradlew android:assembleDebug --q`.

## Риски

Несовпадение протокола с srtla_rec → сверять с исходником (сделано в M0) + ранняя валидация M1 e2e. Батарея/трафик keep-alive сотовой → requestNetwork только в эфире, релиз в `stop()`, opt-in. NAT/discovery relay → NSD + ручной IP + код + TTL. Термалка → FGS/wake-lock, ABR (M5), отключение линков. Wrap 32-бит seqnum → wrap-safe сравнение (тест на границе). StreamPack открывает caller сразу в `open()` → локальный relay слушает до `startStream`.
