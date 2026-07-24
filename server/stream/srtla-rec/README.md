# srtla_rec перед SRS (для бондинга)

srtla_rec принимает бондированный UDP-поток с телефона, собирает линки в один SRT и отдаёт в SRS.

```
телефон (N линков) ──UDP:5000──▶ srtla_rec ──SRT──▶ SRS :10080 ──▶ HTTP-FLV :8080
```

## Запуск

Отдельно не запускается — сервис в общем [docker-compose.yml](../docker-compose.yml), поднимается
разом со всем остальным через корневой `start.bat` (см. [корневой README](../README.md)).

Образ собирается из [Dockerfile](./Dockerfile) прямо здесь: multi-stage сборка бинарника
`srtla_rec` из исходников [BELABOX/srtla](https://github.com/BELABOX/srtla) (`git clone` + `make`
внутри builder-стадии), так что для сборки нужен только интернет — никаких заранее собранных
образов таскать не надо. `docker compose up --build` пересоберёт при необходимости.

Оба контейнера (`srs` и `srtla-rec`) сидят в общей сети `bondcast-net`, поэтому `srtla_rec`
видит `srs` по имени и форвардит туда SRT.

## В приложении

Бондинг: вкл · Хост srtla_rec: **IP машины с докером** (в той же сети, что телефон) · Порт: `5000`

## Проверка

```bash
docker ps                 # у srtla-rec в PORTS: 0.0.0.0:5000->5000/udp
docker logs -f srtla-rec  # регистрация группы и линков
```
Просмотр: `http://<IP>:8080/live/<имя>.flv`
