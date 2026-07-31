const express = require('express');
const http = require('http');
const Docker = require('dockerode');
const fs = require('fs');
const path = require('path');
const { PassThrough } = require('stream');
const { EventEmitter } = require('events');
const QRCode = require('qrcode');
const { WebSocketServer } = require('ws');
const { OBSWebSocket } = require('obs-websocket-js');

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
// негласно подразумевает генератор имён в app.js (adjective-noun-число).
const STREAM_NAME_RE = /^[a-zA-Z0-9_-]{1,64}$/;
// Имя ведущего идёт в URL оверлея (?host=...) и в innerHTML — не URL-путь и не файловое
// имя, поэтому ограничение мягче: просто разумная длина, без переводов строк.
const HOST_NAME_RE = /^[^\r\n]{1,40}$/;
// GigaAM модели, которые реально пригодны как ASR-декодер для нашего asr.py
// (transcribe() → текст) — не весь список из gigaam._MODEL_HASHES: например "ssl" —
// это self-supervised backbone без текстового выхода, предлагать его в UI бессмысленно.
const ASR_MODELS = ['v3_e2e_rnnt', 'v3_e2e_ctc'];

// Субтитры (asr-obs) — отдельный тяжёлый GPU-контейнер, живёт вне ALLOWED/SPECS:
// его Env зависит от того, к какому стриму сейчас подключили, поэтому generic
// start/stop/recreate ему не подходят — см. /api/captions/* ниже.
const CAPTIONS_CONTAINER = 'asr-worker';
const CAPTIONS_IMAGE = 'bondcast-asr-worker:latest';
const CAPTIONS_OVERLAY_PORT = 8082;
const ENROLL_CONTAINER = 'asr-enroll';
const ENROLL_DURATION_SEC = 15;

// Тот же каталог, что панель монтирует себе на запись для сборки образа
// (docker-compose.yml: ./asr-obs:/build-context/asr-obs) — переиспользуем его и для
// эталона голоса/имени ведущего: панель пишет их напрямую на диск, без похода в
// контейнер asr-worker/asr-enroll, они лишь читают то же самое через свой bind.
const ASR_OBS_DIR = '/build-context/asr-obs';
const REFERENCE_PATH = path.join(ASR_OBS_DIR, 'reference.npy');
const HOST_NAME_PATH = path.join(ASR_OBS_DIR, 'host_name.txt');

function readHostName() {
  try {
    return fs.readFileSync(HOST_NAME_PATH, 'utf8').trim() || null;
  } catch (e) {
    return null;
  }
}

function hasVoiceReference() {
  try {
    const st = fs.statSync(REFERENCE_PATH);
    // size > 0 — иначе пустышка от ensureReferenceFileIsReal() (до первого
    // энроллмента) читается как "эталон есть", и speaker-gate падает на np.load().
    return st.isFile() && st.size > 0;
  } catch (e) {
    return false;
  }
}

// Docker bind-mount несуществующего хостового файла молча создаёт на его месте
// ПУСТУЮ ДИРЕКТОРИЮ (и на хосте, и в контейнере) — не файл. До первого энроллмента
// reference.npy на диске не существует вообще, и как только его первым бинд-маунтит
// любой контейнер (asr-worker или asr-enroll), там появляется директория, на которой
// потом падает np.load()/np.save() внутри Python (уже ловили оба раза). Вызывать перед
// каждым созданием контейнера, который монтирует REFERENCE_PATH — идемпотентно и дёшево.
function ensureReferenceFileIsReal() {
  try {
    const st = fs.statSync(REFERENCE_PATH);
    if (st.isFile()) return;
    fs.rmSync(REFERENCE_PATH, { recursive: true, force: true }); // фантомная директория от прошлого бага
  } catch (e) {
    // ENOENT — файла ещё нет, это нормально, просто создаём ниже
  }
  fs.closeSync(fs.openSync(REFERENCE_PATH, 'w'));
}

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

// Логи отдельно от start/stop/recreate: у asr-worker/asr-enroll нет статичного SPECS
// (Env зависит от текущего стрима/энроллмента), но посмотреть их лог — безопасно и
// нужно для диагностики, так что список для /logs шире, чем ALLOWED.
const LOGGABLE = [...ALLOWED, CAPTIONS_CONTAINER, ENROLL_CONTAINER];
function checkLoggable(req, res, next) {
  if (!LOGGABLE.includes(req.params.name)) {
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

  const entries = localAddresses.map((address) => ({ address, label: address, isPublic: !isPrivateIp(address) }));
  if (publicIp && !localAddresses.includes(publicIp)) {
    entries.push({ address: publicIp, label: `${publicIp} (внешний, нужен проброс портов)`, isPublic: true });
  }

  const rawName = String(req.query.name || 'livestream').trim() || 'livestream';
  const name = STREAM_NAME_RE.test(rawName) ? rawName : 'livestream';

  const hosts = await Promise.all(
    entries.map(async ({ address, label, isPublic }) => {
      // Формат зашит в мобильном парсере (QrPayloadParserImpl.parseBondcast).
      const bondcastUri =
        `bondcast://config?host=${encodeURIComponent(address)}` +
        `&srtlaHost=${encodeURIComponent(address)}&srtlaPort=5000` +
        `&port=10080&name=${encodeURIComponent(name)}&bonding=1`;
      // Рендерим QR на сервере (не в браузере) — так шаг с QR не зависит от CDN.
      const qrDataUrl = await QRCode.toDataURL(bondcastUri, { width: 220, margin: 1 });

      return {
        label,
        isPublic,
        // Разбито на Сервер/Ключ так же, как это два отдельных поля в OBS (Custom → Server/Stream Key) -
        // без имени в конце, чтобы не заставлять пользователя вручную резать готовую ссылку.
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
    `https://check-host.net/check-${proto}?host=${publicIp}:${port}&max_nodes=3`,
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

  // UDP без ответа от порта не отличить от "молча уронили пакет" — единственный
  // надёжный сигнал "закрыто" это ICMP port-unreachable (error не пустой), а "нет
  // ошибки" от ОДНОГО узла ничего не доказывает (у отдельного узла может быть свой
  // сетевой затык на пути, ICMP до него просто не долетел). Раньше здесь стояло
  // .some() — по факту любой один "тихий" узел мог дать ложное "порт открыт", хотя
  // остальные узлы честно видели "Connection refused". Теперь верим порту открытым,
  // только если ни один опрошенный узел не сообщил об ошибке.
  const perNode = Object.values(result || {}).filter((entries) => Array.isArray(entries) && entries[0]);
  if (perNode.length === 0) throw new Error('check-host.net не ответил ни с одного узла');
  return perNode.every((entries) => !entries[0].error);
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
//
// hosting — отдельным полем: ip-api помечает proxy:true почти для ЛЮБОГО IP дата-центра/VPS,
// даже если это просто чей-то сервер, а не прокси/VPN-выход как таковой. У Bondcast сервер
// сам по себе часто и есть такой VPS (см. CLAUDE.md — self-hosted srtla_rec) — для него
// "похоже, включён VPN, выключи его" бессмысленный совет (выключать нечего), вводит в
// заблуждение. Возвращаем оба флага — hintFor() в app.js выбирает подходящий текст сама.
async function ipReputation(ip) {
  try {
    const res = await fetch(`http://ip-api.com/json/${encodeURIComponent(ip)}?fields=proxy,hosting`);
    const data = await res.json();
    return { vpnLikely: Boolean(data.proxy), hostingLikely: Boolean(data.hosting) };
  } catch (e) {
    return { vpnLikely: false, hostingLikely: false };
  }
}

app.get('/api/reachability', async (req, res) => {
  const port = Number(req.query.port) || 5000;
  const proto = req.query.proto === 'tcp' ? 'tcp' : 'udp';

  const localIps = (process.env.HOST_IPS || '').split(',').map((s) => s.trim()).filter(Boolean);
  const localIp = localIps[0];
  if (!localIp) {
    return res.status(502).json({ error: 'HOST_IPS не задан — запусти ярлык «Запустить трансляцию»' });
  }

  // Проверяем внешний (публичный) IP, а не localIp напрямую — если localIp приватный
  // (почти всегда, у любого домашнего роутера), проверка снаружи ВСЕГДА уходила бы в
  // короткое замыкание на reachable:false, даже при рабочем пробросе порта.
  //
  // НО: start.bat (см. корень HOST_IPS) сам уже пытается сначала получить публичный IP
  // через ipify.org с ХОСТА, и только если это не удалось — падает на LAN-адрес через
  // get-host-ips.ps1. Если localIp уже выглядит публичным, это и есть тот самый адрес,
  // что показан в QR/адресах подключения — проверяем ЕГО, а не спрашиваем ipify.org
  // ЗАНОВО из контейнера. У Docker Desktop (WSL2/Hyper-V backend) исходящий трафик
  // контейнера иногда идёт другим сетевым путём, чем у host-процесса start.bat, и
  // ipify.org может отдать РАЗНЫЙ IP изнутри контейнера — тогда проверка молча уходила
  // не по тому адресу, что реально показан пользователю, и порт выглядел "закрытым"
  // просто потому что снаружи никто и не пробрасывал именно этот, никому не показанный IP.
  const targetIp = isPrivateIp(localIp) ? await fetchPublicIp() : localIp;
  if (!targetIp) {
    return res.status(502).json({ localIp, localIps, error: 'Не удалось узнать внешний IP — проверь интернет' });
  }

  const natLikely = isPrivateIp(localIp);
  const { vpnLikely, hostingLikely } = await ipReputation(targetIp);

  try {
    const reachable = await checkPortReachable(targetIp, port, proto);
    res.json({ targetIp, localIp, localIps, natLikely, vpnLikely, hostingLikely, port, proto, reachable });
  } catch (e) {
    res.status(502).json({ targetIp, localIp, localIps, natLikely, vpnLikely, hostingLikely, port, proto, error: e.message });
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

app.get('/api/containers/:name/logs', checkLoggable, async (req, res) => {
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

  // srs/srtla-rec живут долго (restart: unless-stopped) и это раньше не бросалось в
  // глаза, но у asr-worker/asr-enroll контейнер реально останавливается и удаляется —
  // без этого SSE-соединение просто зависало бы открытым (только keep-alive) навсегда.
  logStream.on('end', () => {
    clearInterval(keepAlive);
    res.end();
  });

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
async function getActiveSrsStreams() {
  const srsRes = await fetch('http://srs:1985/api/v1/streams/');
  const data = await srsRes.json();
  return (data.streams || [])
    .filter((s) => s.publish && s.publish.active)
    .map((s) => ({
      name: s.name,
      video: s.video || null,
      audio: s.audio || null,
      kbpsRecv30s: (s.kbps && s.kbps.recv_30s) ?? null,
      // send_30s — то, что SRS суммарно раздаёт ВСЕМ смотрящим этот поток разом
      // (не только превью в панели) — recv/send_bytes аналогично, с начала стрима.
      // Сырые как есть из SRS, без пересчёта в МБ/Мбит — этим занимается фронт
      // (formatKbps/formatBytes в app.js), чтобы не дублировать округление в двух местах.
      kbpsSend30s: (s.kbps && s.kbps.send_30s) ?? null,
      recvBytes: s.recv_bytes ?? null,
      sendBytes: s.send_bytes ?? null,
    }));
}

app.get('/api/streams', async (req, res) => {
  try {
    const streams = await getActiveSrsStreams();
    res.json({ streams: streams.map((s) => ({ ...s, liveSinceMs: streamFirstSeenAt.get(s.name) || null })) });
  } catch (e) {
    res.status(502).json({ error: `не удалось спросить SRS: ${e.message}` });
  }
});

// --- OBS: время жизни стримов + «Умный переключатель сцен» ------------------
// SRS не отдаёт метку начала публикации в своём API — считаем сами: monitorTick()
// ниже опрашивает раз в 2с и запоминает момент первого появления имени в списке
// активных, стирает при исчезновении. Сайдбар "Стримы на сервере" читает это
// через liveSinceMs выше; тот же тик кормит и переключатель сцен ниже.
const streamFirstSeenAt = new Map();

// Панель сидит в Docker-сети, OBS — нативно на хосте (не в контейнере). Раньше
// подключались через HOST_IPS[0]:4455 — тот же адрес, что показываем для
// подключения телефона — но это обычно публичный WAN IP (start.bat спрашивает
// его первым через api.ipify.org). Соединение из контейнера на собственный
// публичный IP требует NAT hairpin/loopback на роутере — на практике роутер
// его не поддерживает и молча рубит соединение (ECONNREFUSED), даже когда OBS
// реально слушает локально и порт 4455 подтверждённо проброшен снаружи. Без
// пароля — тот же принцип, что и везде в панели: авторизация нужна только
// когда управляешь OBS не из локальной сети, а это соединение всегда локальное.
const obs = new OBSWebSocket();
let obsConnected = false;
obs.on('ConnectionOpened', () => { obsConnected = true; });
obs.on('ConnectionClosed', () => { obsConnected = false; });

// host.docker.internal — спецхост Docker Desktop для связи контейнер → хост,
// не зависит от роутера/порт-форвардинга/HOST_IPS. 127.0.0.1 — фолбэк на случай
// локального запуска `node server.js` прямо на Windows без контейнера (см.
// CLAUDE.md, цикл разработки панели). obsHost запоминает, какой вариант
// сработал, чтобы не перебирать оба на каждом реконнекте.
const OBS_HOST_CANDIDATES = ['host.docker.internal', '127.0.0.1'];
let obsHost = null;

async function ensureObsConnected() {
  if (obsConnected) return obs;
  const candidates = obsHost ? [obsHost, ...OBS_HOST_CANDIDATES.filter((h) => h !== obsHost)] : OBS_HOST_CANDIDATES;
  let lastErr;
  for (const host of candidates) {
    try {
      await obs.connect(`ws://${host}:4455`);
      obsHost = host;
      return obs;
    } catch (e) {
      lastErr = e;
    }
  }
  throw lastErr;
}

let sceneSwitcher = {
  enabled: false,
  watchStreamName: null,
  fallbackScene: null,
  delaySec: 3,
  state: 'idle', // idle (выключен) | watching (включён, ждёт) | switched (сейчас на резервной сцене)
  lastError: null,
};
let rememberedLiveScene = null; // сцена, на которую вернёмся, когда сигнал появится снова
let pendingSwitchTimer = null;
let watchedStreamWasLive = null; // null — ещё не знаем (только включили/сменили стрим); дальше true/false для детекта фронта

function clearPendingSwitch() {
  if (pendingSwitchTimer) {
    clearTimeout(pendingSwitchTimer);
    pendingSwitchTimer = null;
  }
}

async function switchToFallback() {
  pendingSwitchTimer = null;
  try {
    const client = await ensureObsConnected();
    const current = await client.call('GetCurrentProgramScene');
    rememberedLiveScene = current.sceneName;
    await client.call('SetCurrentProgramScene', { sceneName: sceneSwitcher.fallbackScene });
    sceneSwitcher.state = 'switched';
    sceneSwitcher.lastError = null;
  } catch (e) {
    sceneSwitcher.lastError = e.message;
  }
}

async function switchBackToLive() {
  try {
    const client = await ensureObsConnected();
    if (rememberedLiveScene) {
      await client.call('SetCurrentProgramScene', { sceneName: rememberedLiveScene });
    }
    sceneSwitcher.state = 'watching';
    sceneSwitcher.lastError = null;
  } catch (e) {
    sceneSwitcher.lastError = e.message;
  }
}

// Общий поллинг раз в 2с: обновляет streamFirstSeenAt (аптайм для сайдбара) и,
// если включён переключатель сцен, следит за пропаданием/появлением именно того
// стрима, который выбран в его настройках — переключает через delaySec после
// пропажи (короткая просадка сама отменяет ещё не сработавший таймер) и
// возвращает прежнюю сцену, как только сигнал придёт снова.
async function monitorTick() {
  let streams;
  try {
    streams = await getActiveSrsStreams();
  } catch (e) {
    return; // сеть/SRS моргнули — не считаем это "стрим пропал", просто пропускаем тик
  }

  const liveNames = new Set(streams.map((s) => s.name));
  for (const name of liveNames) {
    if (!streamFirstSeenAt.has(name)) streamFirstSeenAt.set(name, Date.now());
  }
  for (const name of [...streamFirstSeenAt.keys()]) {
    if (!liveNames.has(name)) streamFirstSeenAt.delete(name);
  }

  if (!sceneSwitcher.enabled || !sceneSwitcher.watchStreamName || !sceneSwitcher.fallbackScene) return;
  const isLive = liveNames.has(sceneSwitcher.watchStreamName);

  if (isLive && pendingSwitchTimer) {
    clearPendingSwitch(); // сигнал вернулся раньше, чем истёк delay — переключать не нужно
  } else if (!isLive && watchedStreamWasLive && !pendingSwitchTimer && sceneSwitcher.state !== 'switched') {
    pendingSwitchTimer = setTimeout(switchToFallback, sceneSwitcher.delaySec * 1000);
  } else if (isLive && sceneSwitcher.state === 'switched') {
    await switchBackToLive();
  }
  watchedStreamWasLive = isLive;
}

setInterval(monitorTick, 2000);

app.get('/api/obs/scenes', async (req, res) => {
  try {
    const client = await ensureObsConnected();
    const { scenes } = await client.call('GetSceneList');
    // OBS отдаёт сцены снизу вверх относительно списка в самом OBS — разворачиваем,
    // чтобы порядок в выпадающем списке совпадал с тем, что стример видит в OBS.
    res.json({ scenes: scenes.map((s) => s.sceneName).reverse() });
  } catch (e) {
    res.status(502).json({ error: e.message });
  }
});

app.get('/api/obs/scene-switcher', (req, res) => {
  res.json(sceneSwitcher);
});

app.post('/api/obs/scene-switcher', async (req, res) => {
  const body = req.body || {};
  const enabled = Boolean(body.enabled);
  const watchStreamName = body.watchStreamName != null ? String(body.watchStreamName).trim() : null;
  const fallbackScene = body.fallbackScene != null ? String(body.fallbackScene).trim() : null;
  const rawDelay = Number(body.delaySec);
  const delaySec = Number.isFinite(rawDelay) ? Math.min(60, Math.max(0, rawDelay)) : sceneSwitcher.delaySec;

  if (enabled && (!watchStreamName || !STREAM_NAME_RE.test(watchStreamName))) {
    return res.status(400).json({ error: 'не выбран стрим для отслеживания' });
  }
  if (enabled && !fallbackScene) {
    return res.status(400).json({ error: 'не выбрана резервная сцена' });
  }

  // Смена отслеживаемого стрима или выключение — сбрасываем текущий цикл
  // переключения, чтобы не словить лишнее переключение сцены на стыке смены настроек.
  if (watchStreamName !== sceneSwitcher.watchStreamName || !enabled) {
    clearPendingSwitch();
    if (sceneSwitcher.state === 'switched') await switchBackToLive();
    watchedStreamWasLive = null;
    rememberedLiveScene = null;
  }

  sceneSwitcher.enabled = enabled;
  sceneSwitcher.watchStreamName = enabled ? watchStreamName : null;
  sceneSwitcher.fallbackScene = enabled ? fallbackScene : sceneSwitcher.fallbackScene; // помним выбор даже выключенным
  sceneSwitcher.delaySec = delaySec;
  sceneSwitcher.state = enabled ? 'watching' : 'idle';
  if (enabled) sceneSwitcher.lastError = null;

  res.json(sceneSwitcher);
});

// --- WS-статистика для NOALBS ----------------------------------------------
// NOALBS не умеет в SRS напрямую (нет такого типа stream server), зато у него
// есть generic-тип "WebSocket" для релеев — просто отдаём ему битрейт активного
// стрима в ожидаемом формате поверх уже существующего опроса SRS API.
// RTT SRS не отдаёт (нет такого поля в его HTTP API), поэтому шлём только bitrate —
// в NOALBS-конфиге switcher должен переключать сцены по битрейту, не по RTT.
const wss = new WebSocketServer({ noServer: true, path: '/ws-stats' });

wss.on('connection', (ws, req) => {
  const feed = new URL(req.url, 'http://localhost').searchParams.get('feed') || 'feed1';

  const interval = setInterval(async () => {
    try {
      const [stream] = await getActiveSrsStreams();
      ws.send(
        JSON.stringify({
          type: 'stats',
          timestamp: Date.now(),
          streamId: stream ? stream.name : null,
          feed,
          bitrate: stream ? stream.kbpsRecv30s ?? 0 : 0,
          packetLoss: 0,
          rtt: 0,
          connected: Boolean(stream),
        }),
      );
    } catch {
      // тихо пропускаем тик — NOALBS сам уйдёт в offline по staleTimeoutMs
    }
  }, 1000);

  ws.on('close', () => clearInterval(interval));
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

// Живой энроллмент голоса ведущего (см. /api/captions/enroll) — прогресс/итог
// живёт в памяти процесса так же, как buildStatus у сборки образа: это штучная
// операция на 15-20 секунд, не постоянный поток, переживать перезапуск панели ей
// незачем — единственный переживающий рестарт факт (сам эталон + имя) лежит на
// диске (reference.npy, host_name.txt), читается заново при каждом /status.
let enrollStatus = 'idle'; // idle | running | done | error
let enrollError = null;

// Готовность asr-worker после (пере)подключения — контейнер "Running" сразу, но
// внутри ещё загружается модель GigaAM (~420МБ, минута+ без кэша — см. gigaam-cache
// volume выше) и только потом реально начинает слушать WS/распознавать. Раньше это
// было видно только через docker logs; теперь панель сама следит за логом контейнера
// и переключает индикатор, когда пайплайн реально стартовал.
let captionsReady = false;

async function watchCaptionsReadiness(container) {
  try {
    const logStream = await container.logs({ follow: true, stdout: true, stderr: true });
    const stdout = new PassThrough();
    const stderr = new PassThrough();
    docker.modem.demuxStream(logStream, stdout, stderr);
    const onData = (chunk) => {
      // "websockets.server ... listening" — pipeline() в main.py поднимает WS-сервер
      // одним из первых шагов ПОСЛЕ того, как все тяжёлые модели уже сконструированы
      // (GigaAmAsr.__init__ грузит/качает GigaAM синхронно) — надёжный маркер готовности.
      if (/websockets\.server[\s\S]*listening/.test(chunk.toString('utf8'))) {
        captionsReady = true;
        logStream.destroy();
      }
    };
    stdout.on('data', onData);
    stderr.on('data', onData);
  } catch (e) {
    // контейнер мог уже исчезнуть (быстрый disconnect сразу после connect) — не критично
  }
}

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

function captionsSpec(streamName, asrModel, speakerThreshold) {
  // Эталон голоса появляется только после успешного энроллмента (см.
  // /api/captions/enroll) — до этого speaker-gate явно выключаем, чтобы не
  // упасть на .npy-файле, который либо не существует, либо (баг Docker при
  // отсутствующем bind-source) окажется пустой директорией.
  const speakerEnabled = hasVoiceReference();
  return {
    name: CAPTIONS_CONTAINER,
    Image: CAPTIONS_IMAGE,
    Env: [
      `ASR_OBS_SOURCE_URL=srt://srs:10080?streamid=live/${streamName}`,
      'ASR_OBS_CONFIG=/srv/config.yaml',
      `ASR_OBS_SPEAKER_ENABLED=${speakerEnabled}`,
      `ASR_OBS_ASR_MODEL=${asrModel}`,
      `ASR_OBS_SPEAKER_THRESHOLD=${speakerThreshold}`,
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
        'hf-cache:/root/.cache/huggingface',
        // GigaAM кэширует свой чекпоинт сам (не через huggingface_hub) — без этого
        // тома ~420МБ модели качались заново на каждое подключение (см. progress
        // "N%|...MiB/s" в логах, о котором сообщил пользователь).
        'gigaam-cache:/root/.cache/gigaam',
      ],
      // dockerode говорит с Engine API напрямую, минуя docker-compose —
      // compose-синтаксис deploy.resources.reservations.devices тут не работает,
      // нужен «сырой» Engine API эквивалент того, во что compose его транслирует.
      DeviceRequests: [{ Driver: 'nvidia', Count: 1, Capabilities: [['gpu']] }],
      NetworkMode: NETWORK,
    },
  };
}

function enrollSpec(streamName, durationSec) {
  return {
    name: ENROLL_CONTAINER,
    Image: CAPTIONS_IMAGE,
    Entrypoint: ['python3', '-m', 'app.live_enroll'],
    Cmd: [
      '--source-url', `srt://srs:10080?streamid=live/${streamName}`,
      '--duration', String(durationSec),
      '--out', '/srv/reference.npy',
    ],
    Env: ['ASR_OBS_CONFIG=/srv/config.yaml'],
    HostConfig: {
      RestartPolicy: { Name: 'no' },
      Binds: [
        `${hostPath('asr-obs\\config.yaml')}:/srv/config.yaml:ro`,
        // Не :ro — сюда пишем результат энроллмента.
        `${hostPath('asr-obs\\reference.npy')}:/srv/reference.npy`,
        'hf-cache:/root/.cache/huggingface',
      ],
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
  const hostName = readHostName();
  const overlayUrl = `http://${overlayHost}:${CAPTIONS_OVERLAY_PORT}/index.html` + (hostName ? `?host=${encodeURIComponent(hostName)}` : '');
  const common = {
    overlayUrl,
    imageExists: hasImage,
    buildStatus,
    buildError,
    hostName,
    hasVoiceReference: hasVoiceReference(),
    enrollStatus,
    enrollError,
  };

  try {
    const info = await docker.getContainer(CAPTIONS_CONTAINER).inspect();
    // watchCaptionsReadiness() следит только за контейнером, который панель САМА
    // только что создала — если панель перезапустили, пока asr-worker уже вовсю
    // работал, слушателя больше нет и captionsReady так и остался бы false навсегда.
    // Подстраховка: раз уж не ready, смотрим хвост лога напрямую (дёшево и
    // самовосстанавливается — как только маркер найден, дальше не спрашиваем).
    if (!captionsReady && info.State.Running) {
      try {
        // Большой tail не случайно: маркер печатается один раз сразу после
        // загрузки модели, а дальше на каждую распознанную реплику пишется
        // новая строка — за долгую сессию их может накопиться тысячи, и
        // скромный tail просто не дотянется обратно до старта.
        const tail = await docker.getContainer(CAPTIONS_CONTAINER).logs({ stdout: true, stderr: true, tail: 5000 });
        if (/websockets\.server[\s\S]*listening/.test(tail.toString('utf8'))) captionsReady = true;
      } catch (e) {
        // не критично — просто останется false до следующего опроса
      }
    }
    const env = info.Config.Env || [];
    const findEnv = (key) => {
      const line = env.find((e) => e.startsWith(`${key}=`));
      return line ? line.slice(key.length + 1) : null;
    };
    const envSourceUrl = findEnv('ASR_OBS_SOURCE_URL');
    const m = envSourceUrl && envSourceUrl.match(/streamid=live\/([^&]+)/);
    // Модель/порог, с которыми РЕАЛЬНО запущен текущий контейнер — фронту нужно
    // сравнить с тем, что сейчас выбрано в UI, чтобы понять, есть ли что применять.
    res.json({
      ...common,
      connected: info.State.Running,
      streamName: m ? m[1] : null,
      ready: captionsReady,
      asrModel: findEnv('ASR_OBS_ASR_MODEL'),
      speakerThreshold: findEnv('ASR_OBS_SPEAKER_THRESHOLD'),
    });
  } catch (e) {
    res.json({ ...common, connected: false, streamName: null, ready: false, asrModel: null, speakerThreshold: null });
  }
});

app.post('/api/captions/connect', async (req, res) => {
  const name = String((req.body && req.body.name) || '').trim();
  if (!STREAM_NAME_RE.test(name)) {
    return res.status(400).json({ error: 'некорректное имя стрима' });
  }
  const asrModel = ASR_MODELS.includes(req.body && req.body.asrModel) ? req.body.asrModel : ASR_MODELS[0];
  const rawThreshold = Number(req.body && req.body.speakerThreshold);
  const speakerThreshold = Number.isFinite(rawThreshold) ? Math.min(1, Math.max(0, rawThreshold)) : 0.25;
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

    ensureReferenceFileIsReal();
    await ensureNetwork();
    captionsReady = false;
    const container = await docker.createContainer(captionsSpec(name, asrModel, speakerThreshold));
    await container.start();
    watchCaptionsReadiness(container); // не await — следит в фоне, ответ не блокирует
    res.json({ ok: true, streamName: name });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.post('/api/captions/disconnect', async (req, res) => {
  try {
    await docker.getContainer(CAPTIONS_CONTAINER).remove({ force: true });
    captionsReady = false;
    res.json({ ok: true });
  } catch (e) {
    if (e.statusCode === 404) return res.json({ ok: true });
    res.status(500).json({ error: e.message });
  }
});

// Живая запись голоса ведущего: короткий одноразовый контейнер слушает указанный
// живой стрим ENROLL_DURATION_SEC секунд, режет речь через VAD и усредняет ECAPA-
// эмбеддинги (app/live_enroll.py — то же самое, что офлайновый app/enroll.py по
// wav-файлам, только источник — сам живой SRT-поток). Имя ведущего панель пишет
// сама на диск (host_name.txt) — Python-скрипту про него знать не нужно.
app.post('/api/captions/enroll', async (req, res) => {
  const streamName = String((req.body && req.body.streamName) || '').trim();
  const hostName = String((req.body && req.body.hostName) || '').trim();
  if (!STREAM_NAME_RE.test(streamName)) {
    return res.status(400).json({ error: 'некорректное имя стрима' });
  }
  if (!HOST_NAME_RE.test(hostName)) {
    return res.status(400).json({ error: 'введи имя ведущего (до 40 символов, без переводов строк)' });
  }
  if (!PROJECT_ROOT) {
    return res.status(500).json({ error: 'PROJECT_ROOT не задан — запусти ярлык «Запустить трансляцию», а не docker вручную' });
  }
  if (!(await imageExists())) {
    return res.status(409).json({ error: 'образ ещё не собран — нажми «Собрать»' });
  }
  if (enrollStatus === 'running') {
    return res.status(409).json({ error: 'запись уже идёт' });
  }

  enrollStatus = 'running';
  enrollError = null;

  try {
    try {
      await docker.getContainer(ENROLL_CONTAINER).remove({ force: true });
    } catch (e) {
      if (e.statusCode !== 404) throw e;
    }

    ensureReferenceFileIsReal();
    await ensureNetwork();
    const container = await docker.createContainer(enrollSpec(streamName, ENROLL_DURATION_SEC));
    await container.start();
    res.json({ ok: true, durationSec: ENROLL_DURATION_SEC });

    // Не блокируем ответ ожиданием записи (15+ секунд) — статус и логи клиент
    // дальше сам опрашивает/стримит (/api/captions/status, .../logs).
    container
      .wait()
      .then(({ StatusCode }) => {
        if (StatusCode === 0) {
          fs.writeFileSync(HOST_NAME_PATH, hostName, 'utf8');
          enrollStatus = 'done';
        } else {
          enrollStatus = 'error';
          enrollError = `запись завершилась с кодом ${StatusCode} — смотри лог`;
        }
      })
      .catch((e) => {
        enrollStatus = 'error';
        enrollError = e.message;
      })
      .finally(() => {
        container.remove({ force: true }).catch(() => {});
      });
  } catch (e) {
    enrollStatus = 'error';
    enrollError = e.message;
    res.status(500).json({ error: e.message });
  }
});

const port = process.env.PORT || 8081;
const server = http.createServer(app);

server.on('upgrade', (req, socket, head) => {
  if (new URL(req.url, 'http://localhost').pathname !== '/ws-stats') {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
});

server.listen(port, () => console.log(`stream-panel listening on :${port}`));
