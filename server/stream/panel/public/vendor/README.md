# vendor/

`mpegts-1.7.3.min.js` — скопирован как есть с самого SRS (`players/js/mpegts-1.7.3.min.js`
внутри образа `ossrs/srs:v6.0-r0`), не из npm. Обслуживается с origin панели (не с SRS,
порт 8080), потому что:

1. SRS отдаёт статику без `Access-Control-Allow-Origin` — при загрузке скриптом с
   `crossorigin="anonymous"` браузер просто откажется его выполнять. Без этого атрибута
   скрипт грузится, но необработанные promise-исключения из него браузер репортит как
   cross-origin script error, глуша `unhandledrejection` на странице панели — увидеть/
   подавить их оттуда просто нельзя (см. openPreview/closePreview в app.js).
2. Не тянуть плеер отдельно, если панель когда-нибудь будет работать без SRS в той же
   docker-сети/хосте.

Лицензия — MIT (upstream: https://github.com/xqq/mpegts.js). Обновлять вручную: скачать
новую версию с самого SRS (`curl http://<srs-host>:8080/players/js/mpegts-X.Y.Z.min.js`)
и поменять путь в `loadMpegts()` (app.js).
