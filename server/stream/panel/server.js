const express = require('express');
const Docker = require('dockerode');
const path = require('path');
const { PassThrough } = require('stream');
const { EventEmitter } = require('events');
const QRCode = require('qrcode');

// Внутри Linux-контейнера (Dockerfile) сокет всегда /var/run/docker.sock.
// При локальном запуске на Windows (без контейнера) dockerode сам находит named pipe Docker Desktop.
const docker = process.env.DOCKER_SOCKET ? new Docker({ socketPath: process.env.DOCKER_SOCKET }) : new Docker();

// Панель умеет управлять только этими контейнерами — сознательно не даём
// произвольный docker-контроль через API, раз панель торчит в сеть.
const ALLOWED = ['srs', 'srtla-rec'];

// Параметры для пересоздания контейнера с нуля, если его удалили (docker rm),
// а не просто остановили. Повторяют сервисы из корневого docker-compose.yml.
//
// PROJECT_ROOT — абсолютный Windows-путь к docs/stream на хосте, пробрасывается
// из docker-compose.yml (задаёт start.bat). Он нужен только здесь: Docker Engine
// API принимает bind-mount строго как хостовый путь, а не путь внутри контейнера
// панели, поэтому просто смонтировать "./srs/srs.conf" как в compose нельзя.
const PROJECT_ROOT = process.env.PROJECT_ROOT;
const hostPath = (relWindowsPath) => `${PROJECT_ROOT}\\${relWindowsPath}`;

const NETWORK = 'bondcast-net';

// Имя стрима идёт в SRT streamid / URL-адреса — то же ограничение символов, что уже
// негласно подразумевает генератор имён в app.js/setup.js (adjective-noun-число).
const STREAM_NAME_RE = /^[a-zA-Z0-9_-]{1,64}$/;

// Субтитры (asr-obs) — отдельный тяжёлый GPU-контейнер, живёт вне ALLOWED/SPECS:
// его Env зависит от того, к какому стриму сейчас подключили, поэтому generic
// start/stop/recreate ему не подходят — см. /api/captions/* ниже.
const CAPTIONS_CONTAINER = 'asr-worker';
const CAPTIONS_IMAGE = 'bondcast-asr-worker:latest';
const CAPTIONS_OVERLAY_PORT = 8082;

const SPECS = {
  srs: {
    name: 'srs',
    Image: 'ossrs/srs:v6.0-r0',
    Cmd: ['./objs/srs', '-c', 'conf/srs.conf'],
    ExposedPorts: { '1935/tcp': {}, '1985/tcp': {}, '8080/tcp': {}, '10080/udp': {} },
    HostConfig: {
      RestartPolicy: { Name: 'unless-stopped' },
      PortBindings: {
        '1935/tcp': [{ HostPort: '1935' }],
        '1985/tcp': [{ HostPort: '1985' }],
        '8080/tcp': [{ HostPort: '8080' }],
        '10080/udp': [{ HostPort: '10080' }],
      },
      Binds: [`${hostPath('srs\\srs.conf')}:/usr/local/srs/conf/srs.conf`],
      NetworkMode: NETWORK,
    },
  },
  'srtla-rec': {
    name: 'srtla-rec',
    Image: 'srtla-rec:latest',
    Entrypoint: ['srtla_rec'],
    Cmd: ['5000', 'srs', '10080'],
    ExposedPorts: { '5000/udp': {} },
    HostConfig: {
      RestartPolicy: { Name: 'unless-stopped' },
      PortBindings: { '5000/udp': [{ HostPort: '5000' }] },
      NetworkMode: NETWORK,
    },
  },
};

async function ensureNetwork() {
  try {
    await docker.createNetwork({ Name: NETWORK });
  } catch (e) {
    if (e.statusCode !== 409) throw e; // 409 = уже существует, это ок
  }
}

const app = express();
app.use(express.json());

function auth(req, res, next) {
  const user = process.env.PANEL_USER;
  const pass = process.env.PANEL_PASS;
  if (!user || !pass) return next();

  const header = req.headers.authorization || '';
  const [, encoded] = header.split(' ');
  const decoded = encoded ? Buffer.from(encoded, 'base64').toString() : '';
  if (decoded === `${user}:${pass}`) return next();

  res.set('WWW-Authenticate', 'Basic realm="stream-panel"');
  return res.status(401).send('Auth required');
}
app.use(auth);
app.use(express.static(path.join(__dirname, 'public')));

function checkAllowed(req, res, next) {
  if (!ALLOWED.includes(req.params.name)) {
    return res.status(403).json({ error: `container "${req.params.name}" is not managed by this panel` });
  }
  next();
}

app.get('/api/connections', async (req, res) => {
  // os.networkInterfaces() тут бесполезен — панель сама сидит в Docker-сети и видит
  // только свой внутренний bridge-IP, а не реальный LAN-адрес хоста. Поэтому список
  // хостовых IP вычисляет start.bat (PowerShell, снаружи контейнеров) и прокидывает
  // через переменную окружения HOST_IPS (см. docker-compose.yml).
  const localAddresses = (process.env.HOST_IPS || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);

  // get-host-ips.ps1 видит только LAN-адреса физических адаптеров — за NAT/роутером
  // это не тот адрес, на который телефон сможет достучаться снаружи. Свой внешний IP
  // комп тоже не знает сам (это адрес роутера на его WAN-стороне) — проще спросить
  // публичный сервис, чем выковыривать его локально. Тот же приём уже используется
  // в /api/reachability. Само собой, работает только если на роутере настроен проброс
  // портов на этот комп — панель это не проверяет, только показывает адрес.
  const publicIp = await fetchPublicIp();

  const entries = localAddresses.map((address) => ({ address, label: address }));
  if (publicIp && !localAddresses.includes(publicIp)) {
    entries.push({ address: publicIp, label: `${publicIp} (внешний, нужен проброс портов)` });
  }

  const rawName = String(req.query.name || 'livestream').trim() || 'livestream';
  const name = STREAM_NAME_RE.test(rawName) ? rawName : 'livestream';

  const hosts = await Promise.all(
    entries.map(async ({ address, label }) => {
      // Формат зашит в мобильном парсере (QrPayloadParserImpl.parseBondcast).
      const bondcastUri =
        `bondcast://config?host=${encodeURIComponent(address)}` +
        `&srtlaHost=${encodeURIComponent(address)}&srtlaPort=5000` +
        `&port=10080&name=${encodeURIComponent(name)}&bonding=1`;
      // Рендерим QR на сервере (не в браузере) — так шаг с QR не зависит от CDN.
      const qrDataUrl = await QRCode.toDataURL(bondcastUri, { width: 220, margin: 1 });

      return {
        label,
        // Разбито на Сервер/Ключ так же, как это два отдельных поля в OBS (Custom → Server/Stream Key) -
        // без имени в конце, чтобы не заставлять пользователя вручную резать готовую RTMP-ссылку.
        obsRtmpServer: `rtmp://${address}:1935/live`,
        obsSrtUrl: `srt://${address}:10080`,
        obsSrtStreamId: `#!::r=live/${name},m=publish`,
        playFlv: `http://${address}:8080/live/${name}.flv`,
        playHls: `http://${address}:8080/live/${name}.m3u8`,
        playSrt: `srt://${address}:10080?streamid=#!::r=live/${name},m=request`,
        mobileSrtlaHost: address,
        mobileSrtlaPort: 5000,
        bondcastUri,
        qrDataUrl,
      };
    }),
  );

  res.json({ name, hosts });
});

// Внешний IP этого компа — не вычислить локально (это WAN-адрес роутера), поэтому
// спрашиваем публичный сервис (бесплатный, без ключа). Используется и в /api/connections
// (для списка адресов), и в /api/reachability (для пометки про VPN).
async function fetchPublicIp() {
  try {
    const res = await fetch('https://api.ipify.org?format=json');
    return (await res.json()).ip;
  } catch (e) {
    return null;
  }
}

// Проверка «виден ли порт снаружи» — своей внешней точки у нас нет, поэтому дёргаем
// check-host.net (публичный, бесплатный, без ключа): он шлёт TCP/UDP-пробу со своих узлов
// и смотрит, вернулся ли ICMP-unreachable/таймаут — не требует ответа от нашего сервиса.
async function checkPortReachable(publicIp, port, proto) {
  const submitRes = await fetch(
    `https://check-host.net/check-${proto}?host=${publicIp}:${port}&max_nodes=2`,
    { headers: { Accept: 'application/json' } },
  );
  const submit = await submitRes.json();
  if (!submit.ok) throw new Error('check-host.net отклонил запрос');

  // Узлы отвечают асинхронно — опрашиваем, пока все не отдадут результат (или не кончится время).
  let result = null;
  for (let i = 0; i < 6; i++) {
    await new Promise((r) => setTimeout(r, 1500));
    const pollRes = await fetch(`https://check-host.net/check-result/${submit.request_id}`, {
      headers: { Accept: 'application/json' },
    });
    result = await pollRes.json();
    if (Object.values(result).every((v) => v !== null)) break;
  }

  const perNode = Object.values(result || {});
  return perNode.some((entries) => Array.isArray(entries) && entries[0] && !entries[0].error);
}

// RFC1918 + loopback/link-local — если адрес из HOST_IPS такой, снаружи его не постучать
// в принципе (это LAN-адрес, не публичный) — нужен либо статик-IP, либо проброс порта.
function isPrivateIp(ip) {
  return /^(10\.|127\.|192\.168\.|169\.254\.)/.test(ip) || /^172\.(1[6-9]|2\d|3[0-1])\./.test(ip);
}

// ip-api.com умеет напрямую сказать, помечен ли конкретный IP как известный VPN/прокси/Tor-
// выход (бесплатно, без ключа) — см. тот же приём в start.bat. Раньше вместо этого сравнивали
// targetIp с "текущим видимым публичным IP" и решали "VPN, если они разошлись" — но если VPN был
// включён и при старте контейнера (когда HOST_IPS зафиксировал внешний IP), и сейчас, оба замера
// совпадают, расхождения не видно, и проверка молчала про VPN, хотя адрес им и остаётся.
async function isKnownVpnExit(ip) {
  try {
    const res = await fetch(`http://ip-api.com/line/${encodeURIComponent(ip)}?fields=proxy`);
    return (await res.text()).trim().toLowerCase() === 'true';
  } catch (e) {
    return false;
  }
}

app.get('/api/reachability', async (req, res) => {
  const port = Number(req.query.port) || 5000;
  const proto = req.query.proto === 'tcp' ? 'tcp' : 'udp';

  const localIps = (process.env.HOST_IPS || '').split(',').map((s) => s.trim()).filter(Boolean);
  // Проверяем именно тот адрес, который уходит телефону в QR/подключении, а не какой-то
  // другой — это и есть то, что реально должно быть доступно снаружи через проброс порта.
  const targetIp = localIps[0];
  if (!targetIp) {
    return res.status(502).json({ error: 'HOST_IPS не задан — запусти ярлык «Запустить трансляцию»' });
  }

  const natLikely = isPrivateIp(targetIp);

  if (natLikely) {
    // Приватный адрес снаружи в принципе не достучаться — незачем спрашивать check-host.net.
    // Его UDP-проверка трактует "нет ответа" как "нет ошибки", а для немаршрутизируемого
    // в интернете адреса ответа не будет никогда — раньше это давало ложный reachable: true.
    return res.json({ targetIp, localIps, natLikely, vpnLikely: false, port, proto, reachable: false });
  }

  const vpnLikely = await isKnownVpnExit(targetIp);

  try {
    const reachable = await checkPortReachable(targetIp, port, proto);
    res.json({ targetIp, localIps, natLikely, vpnLikely, port, proto, reachable });
  } catch (e) {
    res.status(502).json({ targetIp, localIps, natLikely, vpnLikely, port, proto, error: e.message });
  }
});

app.get('/api/status', async (req, res) => {
  const results = await Promise.all(
    ALLOWED.map(async (name) => {
      try {
        const info = await docker.getContainer(name).inspect();
        return {
          name,
          found: true,
          running: info.State.Running,
          state: info.State.Status,
          startedAt: info.State.Running ? info.State.StartedAt : null,
        };
      } catch (e) {
        return { name, found: false, running: false, state: 'not_found', startedAt: null };
      }
    })
  );
  res.json(results);
});

app.post('/api/containers/:name/start', checkAllowed, async (req, res) => {
  try {
    await docker.getContainer(req.params.name).start();
    res.json({ ok: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.post('/api/containers/:name/recreate', checkAllowed, async (req, res) => {
  const name = req.params.name;
  if (!PROJECT_ROOT) {
    return res.status(500).json({ error: 'PROJECT_ROOT не задан — запусти ярлык «Запустить трансляцию», а не docker вручную' });
  }
  try {
    try {
      await docker.getContainer(name).inspect();
      return res.status(409).json({ error: `контейнер "${name}" уже существует, используй start` });
    } catch (e) {
      if (e.statusCode !== 404) throw e;
    }

    await ensureNetwork();
    const container = await docker.createContainer(SPECS[name]);
    await container.start();
    res.json({ ok: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.post('/api/containers/:name/stop', checkAllowed, async (req, res) => {
  try {
    await docker.getContainer(req.params.name).stop();
    res.json({ ok: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get('/api/containers/:name/logs', checkAllowed, async (req, res) => {
  res.set({
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  res.flushHeaders();

  const container = docker.getContainer(req.params.name);
  let logStream;
  try {
    logStream = await container.logs({ follow: true, stdout: true, stderr: true, tail: 200 });
  } catch (e) {
    res.write(`event: error\ndata: ${e.message}\n\n`);
    return res.end();
  }

  const send = (chunk) => {
    chunk
      .toString('utf8')
      .split(/\r?\n/)
      .filter(Boolean)
      .forEach((line) => res.write(`data: ${line}\n\n`));
  };

  const stdout = new PassThrough();
  const stderr = new PassThrough();
  docker.modem.demuxStream(logStream, stdout, stderr);
  stdout.on('data', send);
  stderr.on('data', send);

  const keepAlive = setInterval(() => res.write(':keep-alive\n\n'), 15000);

  req.on('close', () => {
    clearInterval(keepAlive);
    logStream.destroy();
  });
});

// --- Стримы (SRS) ---------------------------------------------------------
// Имя стрима генерируется на телефоне заново на каждой сессии стрима — панель
// не может его знать заранее, поэтому спрашивает сам SRS, что сейчас реально
// публикуется. Панель в той же bondcast-net сети, что и srs — резолвит по
// имени контейнера, порт 1985 (SRS HTTP API) наружу пробрасывать не нужно.
app.get('/api/streams', async (req, res) => {
  try {
    const srsRes = await fetch('http://srs:1985/api/v1/streams/');
    const data = await srsRes.json();
    const streams = (data.streams || [])
      .filter((s) => s.publish && s.publish.active)
      .map((s) => ({
        name: s.name,
        video: s.video || null,
        audio: s.audio || null,
        kbpsRecv30s: (s.kbps && s.kbps.recv_30s) ?? null,
      }));
    res.json({ streams });
  } catch (e) {
    res.status(502).json({ error: `не удалось спросить SRS: ${e.message}` });
  }
});

// --- Субтитры (asr-obs) ----------------------------------------------------
// Образ тяжёлый (CUDA + torch + модели, ~10ГБ) — не собирается на обычном
// старте (см. start.bat), а лениво, по кнопке в панели. Прогресс сборки живёт
// в памяти процесса (buildLog/buildEmitter) — переживает не больше одной
// сборки за раз, но это ок: сборка образа - штучная операция, не потоковые логи.
let buildStatus = 'idle'; // idle | building | done | error
let buildError = null;
let buildLog = [];
const buildEmitter = new EventEmitter();
const MAX_BUILD_LOG_LINES = 1000;

function pushBuildLine(line) {
  buildLog.push(line);
  if (buildLog.length > MAX_BUILD_LOG_LINES) buildLog.shift();
  buildEmitter.emit('line', line);
}

async function imageExists() {
  try {
    await docker.getImage(CAPTIONS_IMAGE).inspect();
    return true;
  } catch (e) {
    return false;
  }
}

function captionsSpec(streamName) {
  return {
    name: CAPTIONS_CONTAINER,
    Image: CAPTIONS_IMAGE,
    Env: [
      `ASR_OBS_SOURCE_URL=srt://srs:10080?streamid=live/${streamName}`,
      'ASR_OBS_CONFIG=/srv/config.yaml',
    ],
    ExposedPorts: { '8765/tcp': {} },
    HostConfig: {
      // В отличие от srs/srtla-rec: субтитры привязаны к конкретному стриму
      // текущей сессии — само-воскрешение после ребута Docker Desktop со
      // старым ASR_OBS_SOURCE_URL только запутает, не помогает.
      RestartPolicy: { Name: 'no' },
      PortBindings: { '8765/tcp': [{ HostPort: '8765' }] },
      Binds: [
        `${hostPath('asr-obs\\config.yaml')}:/srv/config.yaml:ro`,
        `${hostPath('asr-obs\\reference.npy')}:/srv/reference.npy:ro`,
      ],
      // dockerode говорит с Engine API напрямую, минуя docker-compose —
      // compose-синтаксис deploy.resources.reservations.devices тут не работает,
      // нужен «сырой» Engine API эквивалент того, во что compose его транслирует.
      DeviceRequests: [{ Driver: 'nvidia', Count: 1, Capabilities: [['gpu']] }],
      NetworkMode: NETWORK,
    },
  };
}

app.post('/api/captions/build', async (req, res) => {
  if (buildStatus === 'building') {
    return res.status(409).json({ error: 'сборка уже идёт' });
  }
  if (!PROJECT_ROOT) {
    return res.status(500).json({ error: 'PROJECT_ROOT не задан — запусти ярлык «Запустить трансляцию», а не docker вручную' });
  }

  buildStatus = 'building';
  buildError = null;
  buildLog = [];

  try {
    const stream = await docker.buildImage(
      { context: '/build-context/asr-obs', src: ['Dockerfile', 'requirements.txt', 'app', 'config.example.yaml'] },
      { t: CAPTIONS_IMAGE },
    );
    docker.modem.followProgress(
      stream,
      (err) => {
        buildStatus = err ? 'error' : 'done';
        buildError = err ? err.message : null;
        pushBuildLine(err ? `[ошибка сборки] ${err.message}` : '[сборка завершена]');
        buildEmitter.emit('done', { ok: !err });
      },
      (event) => {
        pushBuildLine(event.stream || event.status || JSON.stringify(event));
      },
    );
    res.json({ ok: true, status: buildStatus });
  } catch (e) {
    buildStatus = 'error';
    buildError = e.message;
    res.status(500).json({ error: e.message });
  }
});

app.get('/api/captions/build/logs', (req, res) => {
  res.set({ 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
  res.flushHeaders();

  // Догоняем уже накопленный лог (клиент мог подключиться после старта сборки),
  // дальше — только новые строки.
  buildLog.forEach((line) => res.write(`data: ${line}\n\n`));

  const onLine = (line) => res.write(`data: ${line}\n\n`);
  const onDone = ({ ok }) => res.write(`event: done\ndata: ${ok}\n\n`);
  buildEmitter.on('line', onLine);
  buildEmitter.on('done', onDone);

  const keepAlive = setInterval(() => res.write(':keep-alive\n\n'), 15000);
  req.on('close', () => {
    clearInterval(keepAlive);
    buildEmitter.off('line', onLine);
    buildEmitter.off('done', onDone);
  });
});

app.get('/api/captions/status', async (req, res) => {
  const hasImage = await imageExists();
  const localIps = (process.env.HOST_IPS || '').split(',').map((s) => s.trim()).filter(Boolean);
  const overlayHost = localIps[0] || 'localhost';
  const overlayUrl = `http://${overlayHost}:${CAPTIONS_OVERLAY_PORT}/index.html`;

  try {
    const info = await docker.getContainer(CAPTIONS_CONTAINER).inspect();
    const envSourceUrl = (info.Config.Env || []).find((e) => e.startsWith('ASR_OBS_SOURCE_URL='));
    const m = envSourceUrl && envSourceUrl.match(/streamid=live\/([^&]+)/);
    res.json({
      connected: info.State.Running,
      streamName: m ? m[1] : null,
      overlayUrl,
      imageExists: hasImage,
      buildStatus,
      buildError,
    });
  } catch (e) {
    res.json({ connected: false, streamName: null, overlayUrl, imageExists: hasImage, buildStatus, buildError });
  }
});

app.post('/api/captions/connect', async (req, res) => {
  const name = String((req.body && req.body.name) || '').trim();
  if (!STREAM_NAME_RE.test(name)) {
    return res.status(400).json({ error: 'некорректное имя стрима' });
  }
  if (!PROJECT_ROOT) {
    return res.status(500).json({ error: 'PROJECT_ROOT не задан — запусти ярлык «Запустить трансляцию», а не docker вручную' });
  }
  if (!(await imageExists())) {
    return res.status(409).json({ error: 'образ ещё не собран — нажми «Собрать»' });
  }

  try {
    try {
      await docker.getContainer(CAPTIONS_CONTAINER).remove({ force: true });
    } catch (e) {
      if (e.statusCode !== 404) throw e;
    }

    await ensureNetwork();
    const container = await docker.createContainer(captionsSpec(name));
    await container.start();
    res.json({ ok: true, streamName: name });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.post('/api/captions/disconnect', async (req, res) => {
  try {
    await docker.getContainer(CAPTIONS_CONTAINER).remove({ force: true });
    res.json({ ok: true });
  } catch (e) {
    if (e.statusCode === 404) return res.json({ ok: true });
    res.status(500).json({ error: e.message });
  }
});

const port = process.env.PORT || 8081;
app.listen(port, () => console.log(`stream-panel listening on :${port}`));
