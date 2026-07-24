# План: серверный ASR → субтитры «ведущий/гость» в OBS

> Прогресс: **M0 — каркас** (структура сервиса, интерфейсы, docker) ✅ · **M1 — прогон на живом
> SRS** (реальный эфир, распознавание подтверждено) ✅ · **M2 — host/guest** (живой энроллмент голоса
> ведущего кнопкой в панели — слушает 15с текущий эфир, режет речь VAD'ом, считает ECAPA-эмбеддинг;
> имя ведущего показывается в оверлее вместо общего «Ведущий»; `speaker_threshold` пока дефолтный
> 0.25, точный тюнинг на разных голосах впереди) ✅ · **M3 — стриминг/качество** (partial-субтитры,
> стики-ярлык спикера, докатка текста, авто-реконнект ffmpeg) ✅ · интеграция в панель BondcastStream
> (`server/stream/panel`) — список активных стримов, кнопки «Собрать»/«Записать голос»/«Подключить
> субтитры» ✅.
> Целевое железо: сервер с GPU (T4/RTX 3060+). Всё open-source, без облачных счётов.

## Context

Телефон шлёт SRT/SRTLA → `srtla_rec` → SRS. Хотим на **сервере** брать аудиодорожку эфира,
распознавать русскую речь и выводить субтитры в **OBS**, разделяя реплики на **«Ведущий»** и **«Гость»**
(IRL: сам стример + окружающие). Всё self-hosted, бесплатно.

Ключевая мысль: это **не** полная диаризация на N спикеров, а **target-speaker** — голос ведущего
регистрируется один раз (энроллмент), дальше каждая реплика сверяется с эталоном. VAD-сегменты
служат единицами атрибуции: ASR даёт текст реплики, ECAPA-эмбеддинг решает host/guest.

## Поток данных

```
srtla_rec → SRS
   → ffmpeg pull (srt://|rtmp://|http-flv) → PCM 16 kHz mono
   → Silero VAD (нарезка на реплики с таймстампами)
        ├─ GigaAM-v3 (v3_e2e_rnnt) → текст реплики (с пунктуацией)
        └─ SpeechBrain ECAPA-TDNN → embedding → cosine vs эталон → host/guest
   → CaptionEvent{speaker, text, start, end, final}
   → WebSocket
   → OBS Browser Source (ведущий/гость разными цветами)
```

## Стек (open, лицензии)

| Звено | Софт | Лицензия | Заметки |
|---|---|---|---|
| Забор аудио | ffmpeg | LGPL/GPL | демукс AAC из TS в PCM 16k |
| VAD | silero-vad (`VADIterator`) | MIT | стриминговая нарезка, CPU |
| ASR (RU) | GigaAM `v3_e2e_rnnt` | MIT | GPU; `transcribe()` ≤25с → кормим по репликам |
| Кто говорит | speechbrain `spkrec-ecapa-voxceleb` | Apache-2.0 | эмбеддинг реплики, cosine + порог |
| Транспорт | `websockets` | BSD | broadcast JSON в оверлей |
| Оверлей | статичный HTML в OBS Browser Source | — | цвет по спикеру, промежуточные серым |

GigaAM `v3_e2e_rnnt` выбран из-за пунктуации и нормализации из коробки (читаемые субтитры).
Реплики ≤25с гарантирует VAD; длиннее — принудительный флаш по таймауту.

## Архитектура (интерфейс-композиция, по конвенции проекта)

Каждое звено — интерфейс (Protocol) + `Impl` + при нужде `WithLogging` + lowercase-фабрика:
- `AudioSource` → `FfmpegAudioSource` → `audioSource()`
- `Vad` → `SileroVad` → `vad()`
- `Asr` → `GigaAmAsr` → `AsrWithLogging` → `asr()`
- `SpeakerGate` → `EcapaSpeakerGate` → `speakerGate()`
- `Publisher` → `WsPublisher` → `publisher()`
- `pipeline()` сшивает: PCM → VAD-сегменты → (ASR ∥ SpeakerGate) → `CaptionEvent` → Publisher.

Тяжёлый инференс (torch) — в thread-executor; WebSocket — на asyncio; события идут через очередь.

## Пакеты/файлы

`server/stream/asr-obs/` (sibling к `srs/`/`srtla-rec/`/`panel/` в общем стеке BondcastStream —
обязательно для относительных путей у Inno Setup и build-context docker-compose):
- `app/config.py` — конфиг (URL SRS, порог спикера, путь эталона, порт WS); `source_url` можно
  переопределить env-переменной `ASR_OBS_SOURCE_URL` — панель так подставляет текущий выбранный стрим.
- `app/events.py` — `CaptionEvent`.
- `app/audio_source.py` — `AudioSource`/`FfmpegAudioSource`/`audioSource()`.
- `app/vad.py` — `Vad`/`SileroVad`/`vad()`.
- `app/asr.py` — `Asr`/`GigaAmAsr`/`AsrWithLogging`/`asr()`.
- `app/speaker.py` — `SpeakerGate`/`EcapaSpeakerGate`/`speakerGate()`.
- `app/publisher.py` — `Publisher`/`WsPublisher`/`publisher()`.
- `app/pipeline.py` — `pipeline()`.
- `app/enroll.py` — CLI энроллмента ведущего офлайн (wav-файлы → эталонный эмбеддинг).
- `app/live_enroll.py` — то же самое, но источник — сам живой SRT-поток (15с записи, VAD режет
  речь, ECAPA-эмбеддинги усредняются); запускает панель одноразовым контейнером `asr-enroll`.
- `app/main.py` — точка входа.
- `overlay/index.html` — оверлей для OBS; `?host=<имя>` в URL подменяет надпись «Ведущий».
- `Dockerfile`, `docker-compose.yml` (standalone, для изолированной разработки/тестов),
  `requirements.txt`, `config.example.yaml`, `config.yaml` (реальный, трекаемый дефолт для интеграции
  с панелью — `speaker_enabled: false` по умолчанию, панель включает через `ASR_OBS_SPEAKER_ENABLED`
  при подключении, если эталон уже записан), `README.md`.

`server/stream/panel/` — управление: `GET /api/streams` (что сейчас реально льётся в SRS),
`POST /api/captions/build` (ленивая сборка GPU-образа), `POST /api/captions/enroll`
(живая запись голоса ведущего с именем — `app/live_enroll.py` в контейнере `asr-enroll`),
`POST /api/captions/connect|disconnect` (пересоздание `asr-worker` под конкретный стрим),
`GET /api/captions/status` (включая `hostName`/`hasVoiceReference`/`enrollStatus`).

## Этапы

- [x] **M0 — каркас**: структура, интерфейсы, docker, оверлей.
- [x] **M1 — текст в OBS**: ffmpeg-тап из SRS → Silero VAD → GigaAM транскрипт → WS → OBS. Прогнано на
  живом SRS-стриме, распознавание подтверждено на реальной речи.
- [ ] **M2 — host/guest**: энроллмент ведущего + ECAPA-верификация на реплику, покраска в оверлее.
  Тюнинг порога (`speaker_threshold`) на реальных голосах — пока стоит `speaker_enabled: false` по
  умолчанию, включается вручную через `python -m app.enroll`.
- [x] **M3 — стриминг/качество**: partial-результаты (VAD эмитит промежуточные сегменты каждые
  `partial_interval_sec`, оверлей обновляет активную строку по `seq`, фиксирует на `final`);
  стабилизация — стики-ярлык спикера на реплику (`min_speaker_sec`) + докатка последнего текста на пустом финале;
  авто-реконнект ffmpeg (супервизор-цикл, `reconnect_delay_sec`). Опц. GigaAM ONNX/TensorRT (fp16) —
  отложено до прогона на GPU (переключатель `asr_backend` в конфиге, когда понадобится латентность).
- [x] **Интеграция в BondcastStream-панель**: `asr-obs` — 4-й сервис общего `docker-compose.yml`
  (профиль `captions`, не поднимается на обычном `start.bat`); панель сама лениво билдит GPU-образ по
  кнопке, показывает список активных стримов из SRS (имя генерируется на телефоне заново каждую
  сессию — захардкодить нельзя) с кнопками «Смотреть»/«Подключить субтитры», подставляет
  `ASR_OBS_SOURCE_URL` под выбранный стрим при пересоздании `asr-worker`.

## Открытые вопросы / оговорки

- **Перекрёстная речь** на одном смешанном микрофоне размывает host/guest. Радикальное решение —
  отдельный микрофон ведущего (петличка/BT) отдельным аудио-каналом с телефона: тогда атрибуция по каналу
  (сплит в ffmpeg), ECAPA — лишь подстраховка. Требует двухканального захвата на клиенте (Фаза 2 бэклог: BT-микрофон).
- GigaAM longform тянет pyannote (нужен `HF_TOKEN`); нам longform не нужен — режем Silero VAD'ом сами.
- Латентность = длина реплики + инференс (~0.5–2с на GPU). Для субтитров приемлемо.
