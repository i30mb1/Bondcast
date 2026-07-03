# srtla_rec перед SRS (для бондинга)

srtla_rec принимает бондированный UDP-поток с телефона, собирает линки в один SRT и отдаёт в SRS.

```
телефон (N линков) ──UDP:5000──▶ srtla_rec ──SRT──▶ SRS :10080 ──▶ HTTP-FLV :8080
```

## Запуск (Docker Desktop, Git Bash) — одной командой

SRS уже поднят контейнером `srs`. Команда создаёт общую сеть, подключает к ней SRS,
пересоздаёт srtla_rec с публикацией порта и форвардом в SRS:

```bash
docker network create bondcast-net 2>/dev/null; docker network connect bondcast-net srs 2>/dev/null; docker rm -f srtla-rec 2>/dev/null; docker run -d --name srtla-rec --network bondcast-net -p 5000:5000/udp --restart unless-stopped --entrypoint srtla_rec srtla-rec 5000 srs 10080
```

Что делает команда:
- `--network bondcast-net` + `srs 10080` — srtla_rec видит контейнер `srs` по имени и форвардит SRT в него.
- `-p 5000:5000/udp` — публикует UDP-порт наружу (**`/udp` обязателен**, иначе телефон не достучится).
- `srtla_rec 5000 srs 10080` — слушать SRTLA на 5000, форвардить SRT в `srs:10080`.

Первый раз собрать образ: `docker build -t srtla-rec dev-server/srtla-rec`

## В приложении

Бондинг: вкл · Хост srtla_rec: **IP машины с докером** (в той же сети, что телефон) · Порт: `5000`

## Проверка

```bash
docker ps                 # у srtla-rec в PORTS: 0.0.0.0:5000->5000/udp
docker logs -f srtla-rec  # регистрация группы и линков
```
Просмотр: `http://<IP>:8080/live/<имя>.flv`
