# asr-obs — серверные субтитры с распознаванием голосов в OBS

Берёт аудиодорожку эфира из SRS, распознаёт русскую речь (GigaAM-v3), сопоставляет
реплики с одним из нескольких именованных голосов (SpeechBrain ECAPA по эталонам,
каждый со своим порогом схожести) и шлёт субтитры в OBS по WebSocket. Всё
open-source, GPU. Подробный план и этапы — `../../docs/asr-obs-plan.md`.

## Поток

```
SRS -> ffmpeg -> Silero VAD -> [GigaAM-v3 текст | ECAPA: голос из voices/ или "Кто-то"] -> WebSocket -> OBS Browser Source
```

## Запуск

1. Скопировать конфиг и указать `source_url` на свой SRS-выход:
   ```bash
   cp config.example.yaml config.yaml
   ```
2. (опционально) Энроллмент голоса — 5-10 wav по 3-5с, `<id>` — любая уникальная
   строка (имя файла эталона):
   ```bash
   python3 -m app.enroll "host/*.wav" --out voices/<id>.npy
   ```
   и добавить в `voices/voices.json` запись `{"id": "<id>", "name": "Женя",
   "threshold": 0.25}` (панель делает это сама через `/api/captions/enroll` +
   `PATCH .../voices/:id`, вручную нужно только для прямого запуска в обход неё).
   Без `voices/voices.json` (или без валидных `.npy` в нём) сервис помечает все
   реплики как «Кто-то».
3. Поднять:
   ```bash
   docker compose up --build
   ```
4. В OBS добавить **Browser Source**: `http://<сервер>:8080/index.html`
   (при другом хосте WS: `...index.html?ws=ws://<сервер>:8765`).

## Тюнинг

- `threshold` в `voices.json` — порог cosine similarity для конкретного голоса
  (выше = строже, чаще не узнаёт и подписывает «Кто-то»); правится на лету, без
  пересоздания контейнера.
- `max_segment_sec` — принудительный флаш длинной реплики (GigaAM transcribe до 25с).
- CPU-фолбэк: сменить `device: cpu` и в requirements заменить GigaAM на faster-whisper.
