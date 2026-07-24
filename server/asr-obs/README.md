# asr-obs — серверные субтитры «ведущий/гость» в OBS

Берёт аудиодорожку эфира из SRS, распознаёт русскую речь (GigaAM-v3), делит реплики на
**Ведущий/Гость** (SpeechBrain ECAPA по эталону) и шлёт субтитры в OBS по WebSocket.
Всё open-source, GPU. Подробный план и этапы — `../../docs/asr-obs-plan.md`.

## Поток

```
SRS -> ffmpeg -> Silero VAD -> [GigaAM-v3 текст | ECAPA host/guest] -> WebSocket -> OBS Browser Source
```

## Запуск

1. Скопировать конфиг и указать `source_url` на свой SRS-выход:
   ```bash
   cp config.example.yaml config.yaml
   ```
2. (для host/guest) Энроллмент ведущего — 5-10 wav по 3-5с:
   ```bash
   python3 -m app.enroll "host/*.wav" --out reference.npy
   ```
   Без `reference.npy` сервис помечает все реплики как `host`.
3. Поднять:
   ```bash
   docker compose up --build
   ```
4. В OBS добавить **Browser Source**: `http://<сервер>:8080/index.html`
   (при другом хосте WS: `...index.html?ws=ws://<сервер>:8765`).

## Тюнинг

- `speaker_threshold` — порог cosine similarity (выше = строже к «ведущему»).
- `max_segment_sec` — принудительный флаш длинной реплики (GigaAM transcribe до 25с).
- CPU-фолбэк: сменить `device: cpu` и в requirements заменить GigaAM на faster-whisper.
