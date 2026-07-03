# srtla_rec перед SRS (для бондинга)

SRS не понимает SRTLA. Чтобы принять бондированный поток с телефона, перед SRS ставится
`srtla_rec` из BELABOX/srtla: он слушает UDP-порт, собирает линки в один SRT-поток и форвардит
его в SRT-ингест SRS.

```
телефон (srtla_send, N линков) ──UDP──▶ srtla_rec ──SRT──▶ SRS (10080) ──▶ HTTP-FLV/HLS
```

## Сборка из исходников

```sh
git clone https://github.com/BELABOX/srtla
cd srtla
make            # соберёт srtla_send и srtla_rec
```

## Запуск на сервере (93.84.96.193, рядом с SRS)

`srtla_rec <SRTLA_PORT> <SRT_HOST> <SRT_PORT>` — слушает SRTLA на `SRTLA_PORT`,
форвардит SRT на `SRT_HOST:SRT_PORT`.

```sh
# SRS слушает SRT-ингест на 10080 локально
./srtla_rec 5000 127.0.0.1 10080
```

Открыть UDP-порт 5000 в фаерволе:

```sh
sudo ufw allow 5000/udp
```

## Docker (опция)

```dockerfile
FROM debian:bookworm-slim AS build
RUN apt-get update && apt-get install -y git build-essential && rm -rf /var/lib/apt/lists/*
RUN git clone https://github.com/BELABOX/srtla /srtla && make -C /srtla

FROM debian:bookworm-slim
COPY --from=build /srtla/srtla_rec /usr/local/bin/srtla_rec
EXPOSE 5000/udp
ENTRYPOINT ["srtla_rec", "5000", "127.0.0.1", "10080"]
```

```sh
docker build -t srtla-rec .
# --network host, чтобы 127.0.0.1:10080 указывал на SRS хоста
docker run -d --name srtla-rec --network host --restart unless-stopped srtla-rec
```

## Настройки в приложении

Экран настроек → секция «Бондинг (SRTLA)»:
- Включить бондинг: вкл
- Хост srtla_rec: `93.84.96.193`
- Порт srtla_rec: `5000`

Остальное (имя стрима, passphrase, разрешение/битрейт/latency) — как в прямом режиме;
SRT-хендшейк и streamid идут end-to-end до SRS сквозь srtla_rec. Поле «Сервер» (host/port)
при включённом бондинге не используется — SRT уходит на локальный прокси (`127.0.0.1`).

## Проверка

- `docker logs -f srtla-rec` (или вывод srtla_rec) — видно регистрацию группы и линков.
- `docker logs -f srs` — ровный `ikbps` на `live/<имя>`.
- Просмотр: `http://93.84.96.193:8080/live/<имя>.flv`.
