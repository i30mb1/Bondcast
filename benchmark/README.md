# :benchmark — JMH-микробенчмарки горячего пути srtla

Замеряет per-packet путь бондинга (модуль `:kotlin`), чтобы ловить регрессии
производительности «обычного флоу во время стрима». Это чистый JVM/JMH — без
устройства и сети.

## Запуск

```sh
# весь набор + профиль аллокаций (байт/оп)
./gradlew :benchmark:jmh -PjmhArgs="-prof gc"

# один класс/метод (regex) + больше форков для стабильности
./gradlew :benchmark:jmh -PjmhArgs="SchedulerSend -prof gc -f 2"

# список бенчей
./gradlew :benchmark:jmh -PjmhArgs="-l"
```

Тулчейн — JDK 21 (совпадает с рантаймом `:kotlin`). Метрики: `ns/op` (среднее
время) и `gc.alloc.rate.norm` (байт аллокаций на операцию).

## Что меряется

- `SchedulerSendBenchmark` — исходящий видео-пакет: `onEvent(LocalSrtPacket)`.
- `SchedulerControlBenchmark` — входящий ACK/NAK/SRTLA_ACK (скан кольца `pktLog`).
- `SrtInspectorBenchmark` — парсинг пакетов (аллокации `IntArray`/боксинг).
- `SrtlaCodecBenchmark` — сборка reg/keepalive (редкий путь).
- `AbrBenchmark` — `onSample` (раз в секунду).

## База (JDK 21, HotSpot; телефонный ART отличается в абсолюте — важна дельта)

| Бенч | links | ns/op | B/op |
|---|---|---|---|
| sendPacket | 1 / 2 / 3 | 5.0 / 7.6 / 8.4 | 48 |
| srtAck | 1 / 2 / 3 | 114 / 223 / 364 | 40 |
| srtNak | 1 / 2 / 3 | 232 / 417 / 692 | 64 |
| srtlaAck | 1 / 2 / 3 | 367 / 683 / 959 | 32 |
| srtDataSeqnum | — | 0.77 | ~0 |
| srtAckLastAck | — | 0.75 | ~0 |
| srtlaAckSeqnums | — | 5.1 | 48 |
| nakLostSingle | — | 9.0 | 48 |
| nakLostRange | — | 17.2 | 272 |
| keepalive | — | 3.2 | 24 |
| reg1 / reg2 | — | 8.3 | 280 |
| reg2Id | — | 6.0 | 272 |
| onSampleHold | — | 1.6 | 24 |

## Применённые оптимизации (замерено до → после)

- `prevIdx` (`SrtlaScheduler`) и `logPacket` (`LinkState`): modulo → branch + кэш
  размера кольца. Скан кольца в `registerSrtAck/Nak/SrtlaAck` был bound на целочисленное
  деление — ACK/NAK-путь ускорился **~7×** (srtAck 822 → 114 ns при 1 линке).
- `nakLostSeqnums` (`SrtInspector`): боксящий `ArrayList<Int>` → два прохода в `IntArray`.
  range-NAK **266 → 17 ns, 2216 → 272 B/op** (~15×).

## Выводы

- Исходящий путь и так дёшев (5–8 ns, 48 B/op) — не бутылочное горлышко.
- Контрольный путь (ACK/NAK) растёт линейно с числом линков (скан кольца `pktLog` ×
  links), но после снятия modulo стоит уже сотни наносекунд, а не микросекунды.
- Статика недооценила выигрыш от `prevIdx` — реальную дельту дал только замер.
