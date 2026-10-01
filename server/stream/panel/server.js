const express = require('express');
const http = require('http');
const net = require('net');
const Docker = require('dockerode');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { PassThrough } = require('stream');
const { EventEmitter } = require('events');
const QRCode = require('qrcode');
const { WebSocketServer } = require('ws');
const { OBSWebSocket } = require('obs-websocket-js');
const { createLogger } = require('./logger');

// Единый логгер стека: сюда пишет и сама панель, и коллектор докеровских логов
// (см. attachCollector ниже). Папка /logs bind-монтирована с хоста — файл оттуда
// человек пересылает как есть, когда просят разобраться, почему не работает.
const logger = createLogger({
  dir: process.env.LOGS_DIR || '/logs',
  level: process.env.LOG_LEVEL || 'info',
  tzOffsetMin: Number(process.env.TZ_OFFSET_MIN) || 0,
});

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
// Имя голоса идёт в voices.json и в innerHTML оверлея — не URL-путь и не
// файловое имя, поэтому ограничение мягче: просто разумная длина, без переводов строк.
const VOICE_NAME_RE = /^[^\r\n]{1,40}$/;
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
// голосов: панель пишет их напрямую на диск, без похода в контейнер
// asr-worker/asr-enroll, они лишь читают то же самое через свой bind.
const ASR_OBS_DIR = '/build-context/asr-obs';
// Директория (не отдельный файл) с несколькими именованными голосами:
// voices.json — манифест [{id,name,threshold}], <id>.npy — эталон каждого.
// Монтируется целиком в оба контейнера (asr-worker :ro, asr-enroll rw) —
// asr-worker сам следит за mtime манифеста и подхватывает новые/переименованные/
// удалённые голоса живьём, без пересоздания контейнера.
const VOICES_DIR = path.join(ASR_OBS_DIR, 'voices');
const VOICES_MANIFEST_PATH = path.join(VOICES_DIR, 'voices.json');
// Директория создаётся один раз здесь, при старте панели — этим полностью
// устраняется старый баг "bind-mount несуществующего ФАЙЛА молча создаёт
// вместо него директорию": теперь bind-mount'ится директория, которая уже
// существует к моменту первого создания любого контейнера; файлы внутри нёе
// создаются/удаляются штатно, без фантомных директорий на их месте.
fs.mkdirSync(VOICES_DIR, { recursive: true });

function readVoices() {
  try {
    const parsed = JSON.parse(fs.readFileSync(VOICES_MANIFEST_PATH, 'utf8'));
    return Array.isArray(parsed) ? parsed : [];
  } catch (e) {
    return [];
  }
}

function writeVoicesAtomic(voices) {
  // rename — атомарная замена на одной ФС, важно: Python параллельно читает
  // этот же манифест по mtime (может быть прямо во время эфира), временный
  // файл исключает шанс увидеть его недописанным.
  const tmpPath = `${VOICES_MANIFEST_PATH}.tmp`;
  fs.writeFileSync(tmpPath, JSON.stringify(voices, null, 2));
  fs.renameSync(tmpPath, VOICES_MANIFEST_PATH);
}

function voiceEmbeddingPath(id) {
  return path.join(VOICES_DIR, `${id}.npy`);
}

function hasVoiceEmbedding(id) {
  try {
    const st = fs.statSync(voiceEmbeddingPath(id));
    return st.isFile() && st.size > 0;
  } catch (e) {
    return false;
  }
}

// Оформление оверлея субтитров (размер шрифта, кол-во строк, цвета/фон) —
// раньше кодировалось в query-параметрах ссылки для OBS, из-за чего каждую
// правку нужно было заново копировать в Browser Source. Теперь панель хранит
// его сама (этот файл) и отдаёт публично через GET (см. app.get ниже, ДО
// app.use(auth) — статичная страница оверлея на отдельном порту 8082 читает
// анонимно, без логина панели), а overlay/index.html периодически перечитывает
// и применяет живьём — сама ссылка на оверлей остаётся постоянной навсегда.
const OVERLAY_STYLE_PATH = path.join(ASR_OBS_DIR, 'overlay_style.json');
const OVERLAY_STYLE_DEFAULTS = { size: 34, lines: 3, guestColor: '#34c759', bgColor: '#000000', bgOpacity: 60, showSpeaker: true };
const OVERLAY_COLOR_RE = /^#[0-9a-f]{6}$/i;

function readOverlayStyle() {
  try {
    const parsed = JSON.parse(fs.readFileSync(OVERLAY_STYLE_PATH, 'utf8'));
    return { ...OVERLAY_STYLE_DEFAULTS, ...parsed };
  } catch (e) {
    return { ...OVERLAY_STYLE_DEFAULTS };
  }
}

function writeOverlayStyleAtomic(style) {
  const tmpPath = `${OVERLAY_STYLE_PATH}.tmp`;
  fs.writeFileSync(tmpPath, JSON.stringify(style, null, 2));
  fs.renameSync(tmpPath, OVERLAY_STYLE_PATH);
}

// --- Проверка обновлений -----------------------------------------------------
// VERSION пишет installer/setup.iss при установке/обновлении (bind-mount файла,
// не env — см. docker-compose.yml) — читаем заново на каждый запрос, поэтому
// update.ps1 меняет её "на лету", без пересоздания панели: свежая версия видна
// сразу на следующий опрос /api/update/status.
const VERSION_PATH = '/build-context/VERSION';
function readCurrentVersion() {
  try {
    return fs.readFileSync(VERSION_PATH, 'utf8').trim() || 'dev';
  } catch (e) {
    return 'dev';
  }
}

// Числовое сравнение по компонентам (1.10.0 > 1.9.0) - строковое сравнение
// версий врёт на двузначных номерах. Нечисловые версии (напр. "dev") — не
// считаем меньше/больше ничего, isNewer просто вернёт false.
function isVersionNewer(a, b) {
  const pa = String(a).split('.').map(Number);
  const pb = String(b).split('.').map(Number);
  if (pa.some(Number.isNaN) || pb.some(Number.isNaN)) return false;
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const diff = (pa[i] || 0) - (pb[i] || 0);
    if (diff !== 0) return diff > 0;
  }
  return false;
}

// В памяти, переживать перезапуск панели незачем - опрашивается заново при
// каждом старте (тот же принцип, что buildStatus/captionsReady выше).
let latestVersionCache = { version: null, note: null, checkedAt: null };

async function checkForUpdate() {
  try {
    const res = await fetch('https://api.github.com/repos/i30mb1/Bondcast/releases/latest', {
      headers: { 'User-Agent': 'BondcastStream-Panel' },
    });
    if (!res.ok) return;
    const data = await res.json();
    const tag = String(data.tag_name || '').replace(/^v/, '');
    // Первая строка текста релиза — шуточное мини-описание в духе Discord-патчноутов
    // (см. gh release create при публикации), остальное — подробный список изменений,
    // его читают на GitHub, не в баннере панели. Без первой строки просто пусто —
    // не показываем весь markdown-текст релиза как есть.
    const note = String(data.body || '').split('\n').map((l) => l.trim()).find(Boolean) || null;
    if (tag) latestVersionCache = { version: tag, note: note ? note.slice(0, 140) : null, checkedAt: Date.now() };
  } catch (e) {
    // сеть моргнула/GitHub недоступен — не критично, попробуем на следующем тике
  }
}
checkForUpdate();
setInterval(checkForUpdate, 30 * 60 * 1000);

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

// timingSafeEqual требует буферы одной длины - сравниваем хеши фиксированного
// размера вместо сырых строк, иначе сама длина пароля утекала бы через тайминг.
function safeEqual(a, b) {
  const hashA = crypto.createHash('sha256').update(a).digest();
  const hashB = crypto.createHash('sha256').update(b).digest();
  return crypto.timingSafeEqual(hashA, hashB);
}

function auth(req, res, next) {
  const user = process.env.PANEL_USER;
  const pass = process.env.PANEL_PASS;
  if (!user || !pass) return next();

  const header = req.headers.authorization || '';
  const [, encoded] = header.split(' ');
  const decoded = encoded ? Buffer.from(encoded, 'base64').toString() : '';
  if (safeEqual(decoded, `${user}:${pass}`)) return next();

  res.set('WWW-Authenticate', 'Basic realm="stream-panel"');
  return res.status(401).send('Auth required');
}
// Публично, без авторизации — см. комментарий у OVERLAY_STYLE_PATH выше:
// overlay/index.html читает это анонимно с отдельного порта (8082), у него
// нет и не может быть логина панели (PANEL_USER/PANEL_PASS), даже если он задан.
// CORS обязателен по той же причине: 8082 и 8081 — разные origin для браузера,
// без Access-Control-Allow-Origin fetch() со страницы оверлея молча падает
// ("Failed to fetch") ещё до того, как auth/PANEL_PASS вообще стали бы иметь значение.
app.get('/api/captions/overlay-style', (req, res) => {
  res.set('Access-Control-Allow-Origin', '*');
  res.json(readOverlayStyle());
});

// Прогресс тихой установки (update.ps1 на хосте) — панель сама рисует его в
// баннере (см. app.js), а не update.ps1 отдельным окошком: пользователь жал
// кнопку "Обновить" в панели, там же логично видеть, что происходит. update.ps1
// шлёт сюда сам, у него нет способа авторизоваться (тот же принцип, что и
// GET выше) — публично, до auth. GET ниже панель опрашивает обычным способом,
// уже за auth, как всё остальное.
let updateProgress = { status: 'idle', percent: 0, message: '' };
let lastLoggedUpdateStatus = null;
app.post('/api/update/progress', (req, res) => {
  const body = req.body || {};
  updateProgress = {
    status: String(body.status || 'idle'),
    percent: Math.min(100, Math.max(0, Number(body.percent) || 0)),
    message: String(body.message || ''),
  };
  // Установщик шлёт прогресс часто; в лог кладём только смену статуса, иначе
  // получим сотню строк «идёт загрузка, 37%».
  if (updateProgress.status !== lastLoggedUpdateStatus) {
    lastLoggedUpdateStatus = updateProgress.status;
    logger.log('panel', 'info', `обновление: ${updateProgress.status} ${updateProgress.message}`.trim());
  }
  res.json({ ok: true });
});

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

// --- Сбор логов сервисов ---------------------------------------------------
// Панель — единственное место, где логи всех контейнеров сходятся в одну
// хронологию. Именно она нужна при расследовании: «телефон отвалился → что в
// этот момент сказал srtla-rec → что SRS». Через docker logs по отдельности
// такую картину не собрать.
const COLLECTED = ['srs', 'srtla-rec', 'overlay', CAPTIONS_CONTAINER];
const collectorStreams = new Map(); // имя контейнера -> активный лог-стрим

// Сервисы пишут в свободной форме, уровня в машинном виде у них нет — достаём
// его из текста, иначе фильтр по уровню во вкладке «Диагностика» бесполезен.
function levelOfServiceLine(line) {
  if (/\b(error|fatal|traceback|panic)\b/i.test(line)) return 'error';
  if (/\bwarn(ing)?\b/i.test(line)) return 'warn';
  return 'info';
}

function attachCollector(name) {
  if (collectorStreams.has(name)) return;
  const container = docker.getContainer(name);
  // tail: 0 — только новое. С ненулевым хвостом после каждого рестарта контейнера
  // в файл заново падал бы кусок уже собранных строк.
  container.logs({ follow: true, stdout: true, stderr: true, tail: 0 }, (err, stream) => {
    if (err || !stream) {
      // Контейнер может ещё не существовать (asr-worker поднимается лениво) —
      // это штатно, подписка случится по docker-событию start.
      logger.log('panel', 'debug', `нет подписки на лог ${name}: ${err ? err.message : 'нет потока'}`);
      return;
    }
    collectorStreams.set(name, stream);

    const stdout = new PassThrough();
    const stderr = new PassThrough();
    docker.modem.demuxStream(stream, stdout, stderr);

    // Чанк докера рвётся по границе буфера, а не по строке — без склейки
    // длинные сообщения (стектрейсы asr-worker) попадали бы в лог кусками.
    let buffer = '';
    const onData = (chunk) => {
      buffer += chunk.toString('utf8');
      const lines = buffer.split(/\r?\n/);
      buffer = lines.pop();
      for (const line of lines) {
        const text = line.trim();
        if (text) logger.log(name, levelOfServiceLine(text), text);
      }
    };
    stdout.on('data', onData);
    stderr.on('data', onData);

    const done = () => {
      if (buffer.trim()) logger.log(name, levelOfServiceLine(buffer), buffer.trim());
      buffer = '';
      collectorStreams.delete(name);
    };
    stream.on('end', done);
    stream.on('error', done);
  });
}

function startCollectors() {
  for (const name of COLLECTED) attachCollector(name);

  // Контейнер могли перезапустить, пересоздать кнопкой в панели или поднять
  // лениво (asr-worker) — старый стрим при этом умирает. Без переподписки лог
  // сервиса замолкал бы навсегда до рестарта самой панели.
  docker.getEvents({ filters: { type: ['container'], event: ['start'] } }, (err, stream) => {
    if (err || !stream) {
      logger.log('panel', 'warn', `не подписаться на события docker: ${err ? err.message : 'нет потока'}`);
      return;
    }
    stream.on('data', (chunk) => {
      let event;
      try {
        event = JSON.parse(chunk.toString('utf8'));
      } catch (e) {
        return; // в одном чанке может приехать несколько событий — пропускаем битые
      }
      const name = event && event.Actor && event.Actor.Attributes && event.Actor.Attributes.name;
      if (!COLLECTED.includes(name)) return;
      logger.log('panel', 'info', `контейнер ${name} запустился — подписываюсь на его лог`);
      collectorStreams.delete(name);
      attachCollector(name);
    });
    stream.on('error', (e) => logger.log('panel', 'warn', `поток событий docker оборвался: ${e.message}`));
  });
}

startCollectors();

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
  // в /api/reachability. Сам по себе этот адрес отвечает, только если на роутере
  // настроен проброс портов — панель это не проверяет, а лишь отдаёт оба варианта
  // (см. scope ниже) и даёт человеку выбрать, откуда он будет стримить.
  const publicIp = await fetchPublicIp();

  // LAN-адрес(а) этой машины — отдельно от HOST_IPS, куда start.bat кладёт ПУБЛИЧНЫЙ
  // адрес. Нужны панели не только для диагностики: по ним работает режим «дома, по
  // Wi-Fi» — телефон в той же сети достучится напрямую, без проброса портов на
  // роутере. Это самый простой первый успех, и раньше его нельзя было даже показать:
  // в QR всегда уходил внешний адрес, а он без проброса не отвечает.
  const lanAddresses = (process.env.LAN_IPS || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);

  // scope — по нему панель выбирает адрес под выбранный режим ('lan' — телефон в той
  // же Wi-Fi, 'internet' — телефон в мобильной сети). Один и тот же адрес может
  // прийти и из LAN_IPS, и из HOST_IPS (когда ipify не ответил и start.bat упал на
  // LAN) — дедуплицируем по адресу, первым выигрывает LAN.
  const entries = [];
  const seen = new Set();
  const addEntry = (address, label) => {
    if (!address || seen.has(address)) return;
    seen.add(address);
    const isPublic = !isPrivateIp(address);
    entries.push({ address, label, isPublic, scope: isPublic ? 'internet' : 'lan' });
  };
  lanAddresses.forEach((address) => addEntry(address, address));
  localAddresses.forEach((address) => addEntry(address, address));
  addEntry(publicIp, publicIp);

  const rawName = String(req.query.name || 'livestream').trim() || 'livestream';
  const name = STREAM_NAME_RE.test(rawName) ? rawName : 'livestream';

  const hosts = await Promise.all(
    entries.map(async ({ address, label, isPublic, scope }) => {
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
        // 'lan' — адрес виден только внутри домашней сети (режим «дома, по Wi-Fi»),
        // 'internet' — публичный адрес, до него нужен проброс порта на роутере.
        scope,
        // Разбито на Сервер/Ключ так же, как это два отдельных поля в OBS (Custom → Server/Stream Key) -
        // без имени в конце, чтобы не заставлять пользователя вручную резать готовую ссылку.
        obsSrtUrl: `srt://${address}:10080`,
        obsSrtStreamId: `#!::r=live/${name},m=publish`,
        // Прямой RTMP-вход в SRS (в обход srtla-rec/бондинга) — для приложений без
        // поддержки SRT, только два поля (URL + ключ), как PRISM Live.
        rtmpUrl: `rtmp://${address}:1935/live`,
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

// Одна страница разом проверяет до пяти портов, и все пять проверок спрашивают про
// ОДИН и тот же внешний адрес — без кэша это пять одинаковых запросов к ipify/ip-api
// на каждый цикл (а у бесплатного ip-api.com лимит 45 запросов в минуту с адреса, за
// которым легко словить временный бан ровно в момент, когда пользователь разбирается,
// почему у него порт закрыт). Кэшируем ПРОМИС, а не результат: пять проверок стартуют
// одновременно, и кэш по готовому значению они все успевали бы промахнуть.
function memoizeAsync(fn, ttlMs) {
  const cache = new Map();
  return (key = '') => {
    const hit = cache.get(key);
    if (hit && Date.now() - hit.at < ttlMs) return hit.promise;
    const promise = Promise.resolve(fn(key))
      // Неудачу не кэшируем — иначе один сетевой сбой замораживал бы "не знаю"
      // на весь TTL, хотя следующая попытка через секунду отработала бы нормально.
      .then((value) => {
        if (value === null || value === undefined) cache.delete(key);
        return value;
      })
      .catch((e) => {
        cache.delete(key);
        throw e;
      });
    cache.set(key, { at: Date.now(), promise });
    return promise;
  };
}

// Внешний IP этого компа — не вычислить локально (это WAN-адрес роутера), поэтому
// спрашиваем публичный сервис (бесплатный, без ключа). Используется и в /api/connections
// (для списка адресов), и в /api/reachability (для пометки про VPN).
const fetchPublicIp = memoizeAsync(async () => {
  try {
    const res = await fetch('https://api.ipify.org?format=json');
    return (await res.json()).ip;
  } catch (e) {
    return null;
  }
}, 30 * 1000);

// check-host.net отдаёт по каждому узлу одну запись: {address,time} — TCP-коннект удался;
// {address,timeout} — UDP, ответа не дождались; {error:"..."} — сырая строка ошибки сокета.
// Раньше эта строка выбрасывалась (смотрели только "есть error или нет"), а в ней и лежит
// самое полезное для диагноза:
//   refused ("Connection refused") — пакет ДОШЁЛ, и на том конце ответили "тут никто не
//     слушает" (TCP RST либо ICMP port unreachable на UDP). Значит путь снаружи внутрь
//     работает, а проблема на приёмной стороне — сервис не поднят или правило проброса
//     ведёт на машину, где его нет.
//   timeout ("Connection timed out") — пакет молча утонул. Так выглядит и отсутствующий
//     проброс, и фаервол с политикой drop, и серый IP провайдера.
// Это два принципиально разных диагноза с разными действиями пользователя — см.
// diagnoseClosed() в app.js. Живьём проверено, что check-host.net отдаёт ровно строку
// "Connection timed out"; остальные варианты ловим по подстроке, формат у них тот же.
function classifyProbeError(raw) {
  const text = String(raw || '').toLowerCase();
  if (text.includes('refus')) return 'refused';
  if (text.includes('timed out') || text.includes('timeout')) return 'timeout';
  if (text.includes('unreach') || text.includes('no route')) return 'unreachable';
  return 'other';
}

// Проверка «виден ли порт снаружи» — своей внешней точки у нас нет, поэтому дёргаем
// check-host.net (публичный, бесплатный, без ключа): он шлёт TCP/UDP-пробу со своих узлов
// и смотрит, вернулся ли ICMP-unreachable/таймаут — не требует ответа от нашего сервиса.
async function probePortExternally(publicIp, port, proto) {
  const submitRes = await fetch(
    `https://check-host.net/check-${proto}?host=${publicIp}:${port}&max_nodes=3`,
    { headers: { Accept: 'application/json' } },
  );
  const submit = await submitRes.json();
  // Свою причину отказа check-host.net кладёт в error (например, упёрлись в лимит
  // проверок с этого адреса) — раньше она терялась, и пользователь видел глухое
  // "отклонил запрос", неотличимое от реальной проблемы с портом.
  if (!submit.ok) throw new Error(`check-host.net отклонил запрос${submit.error ? `: ${submit.error}` : ''}`);

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
  const perNode = Object.entries(result || {})
    .filter(([, entries]) => Array.isArray(entries) && entries[0])
    .map(([node, entries]) => ({ node, error: entries[0].error || null }));
  if (perNode.length === 0) throw new Error('check-host.net не ответил ни с одного узла');

  const failures = perNode.filter((n) => n.error);
  const reachable = failures.length === 0;
  const kinds = [...new Set(failures.map((n) => classifyProbeError(n.error)))];
  return {
    reachable,
    // open / refused / timeout / unreachable / other / mixed (последнее — узлы разошлись,
    // и не в пользу какого-то одного вывода). "refused" перевешивает "timeout", даже если
    // так ответил один узел из трёх: отказ — это положительное свидетельство (пакет дошёл,
    // и на том конце ответили), а таймаут — отсутствие свидетельства (у конкретного узла
    // мог не сложиться маршрут). Та же асимметрия, что и в правиле про "открыт" выше.
    // Замерено вживую на порту 1935: два узла отдали "Connection refused", третий —
    // "Connection timed out"; вывод тут делают первые два.
    verdict: reachable ? 'open' : kinds.includes('refused') ? 'refused' : kinds.length === 1 ? kinds[0] : 'mixed',
    // Замерено вживую: и открытый UDP-порт (8.8.8.8:53), и заведомо закрытый
    // (8.8.8.8:12345) дают одинаковый ответ {address,timeout} без error. То есть
    // "открыт" по UDP — это на самом деле "нам не прислали ICMP-ошибку", молчание
    // фаервола выглядит ровно так же. Честно помечаем, насколько твёрдый вывод:
    // "закрыт" по UDP доказателен (ICMP пришёл), "открыт" — нет.
    conclusive: proto === 'tcp' || !reachable,
    checkedNodes: perNode.length,
    failedNodes: failures.length,
    // Ссылка на человекочитаемый отчёт check-host.net с разбивкой по узлам —
    // для случая "панель говорит закрыт, а я не верю".
    reportUrl: submit.permanent_link || null,
  };
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
// hosting — отдельным полем, но НЕ как признак "это VPS, а не VPN": замеры по ip-api
// показывают, что обычным адресам дата-центров (Hetzner, DigitalOcean, Scaleway, Google)
// ставится proxy:false + hosting:true, а реальному VPN-выходу — proxy:true + hosting:true.
// То есть proxy:true и есть признак VPN/прокси, а hosting лишь уточняет "адрес дата-центра"
// (у Bondcast сервер сам может быть таким VPS, см. CLAUDE.md — self-hosted srtla_rec).
//
// mobile — тот самый флаг, который ставит крест на пробросе портов в принципе: у сотовых
// операторов абонент почти всегда сидит за CGNAT провайдера, публичный адрес общий на
// тысячи абонентов, и пробрасывать на роутере нечего (проверено на ip-api: AS31213
// МегаФон → mobile:true). Раздача с телефона/4G-модем — самый частый случай, когда
// "открыть порт" невозможно, сколько ни правь роутер.
// Возвращаем сырые флаги — diagnoseClosed() в app.js формулирует текст сама.
const ipReputation = memoizeAsync(async (ip) => {
  try {
    const res = await fetch(`http://ip-api.com/json/${encodeURIComponent(ip)}?fields=proxy,hosting,mobile,isp,reverse`);
    const data = await res.json();
    return {
      vpnLikely: Boolean(data.proxy),
      hostingLikely: Boolean(data.hosting),
      mobileLikely: Boolean(data.mobile),
      isp: data.isp || null,
      reverse: data.reverse || null,
    };
  } catch (e) {
    return null;
  }
}, 5 * 60 * 1000);

// Какой из наших контейнеров обязан слушать этот порт. 4455 (управление OBS) сюда не
// входит намеренно: OBS — обычная программа на хосте, а не наш контейнер, для него
// локальную сторону проверяет hostPortListening() ниже.
const PORT_OWNERS = {
  5000: { container: 'srtla-rec', spec: '5000/udp' },
  10080: { container: 'srs', spec: '10080/udp' },
  1935: { container: 'srs', spec: '1935/tcp' },
  8080: { container: 'srs', spec: '8080/tcp' },
};

// Локальная сторона вопроса «почему порт закрыт»: прежде чем отправлять человека
// крутить роутер, стоит убедиться, что на этой машине вообще есть кому отвечать.
// Самые частые причины закрытого порта не имеют к роутеру никакого отношения —
// контейнер остановлен, удалён, крутится в рестарт-цикле или не смог занять порт
// на хосте (его держит другая программа, либо он попал в зарезервированный
// Windows/Hyper-V диапазон — как раз про 5000 это классика).
async function containerPortState(port) {
  const owner = PORT_OWNERS[port];
  if (!owner) return null;
  try {
    const info = await docker.getContainer(owner.container).inspect();
    const bindings = (info.HostConfig && info.HostConfig.PortBindings) || {};
    return {
      container: owner.container,
      found: true,
      running: Boolean(info.State.Running),
      state: info.State.Status,
      restartCount: info.RestartCount || 0,
      exitCode: info.State.ExitCode,
      // Сюда Docker кладёт причину, по которой контейнер не смог стартовать —
      // в частности "Bind for 0.0.0.0:5000 failed: port is already allocated" и
      // виндовое "An attempt was made to access a socket in a way forbidden by
      // its access permissions" (порт попал в зарезервированный диапазон).
      error: info.State.Error || '',
      // Контейнер может быть жив и слушать порт у себя внутри, но не отдавать его
      // наружу — так бывает после ручного docker run/старого контейнера, созданного
      // без -p. Снаружи это неотличимо от закрытого роутера, а лечится совсем иначе.
      published: Array.isArray(bindings[owner.spec]) && bindings[owner.spec].length > 0,
    };
  } catch (e) {
    if (e.statusCode === 404) {
      return { container: owner.container, found: false, running: false, published: false };
    }
    return { container: owner.container, dockerError: e.message };
  }
}

// Дозвон контейнер → хост на тот же порт. Отвечает на вопрос «сервис слушает на этой
// машине?» отдельно от вопроса «пускают ли до него снаружи» — а именно на стыке этих
// двух вопросов и живёт половина причин. host.docker.internal — тот же спецхост, через
// который панель ходит в OBS (см. ensureObsConnected). Только TCP: UDP так не проверить,
// на UDP-порт никто не обязан отвечать.
const HOST_DIAL_CANDIDATES = ['host.docker.internal', '127.0.0.1'];

function dialTcp(host, port, timeoutMs = 1500) {
  return new Promise((resolve) => {
    const socket = new net.Socket();
    let settled = false;
    const finish = (value) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      resolve(value);
    };
    socket.setTimeout(timeoutMs);
    socket.once('connect', () => finish(true));
    socket.once('timeout', () => finish(null)); // локальный коннект не должен «висеть» — считаем неизвестным
    socket.once('error', (e) => finish(e.code === 'ECONNREFUSED' ? false : e.code === 'ENOTFOUND' || e.code === 'EAI_AGAIN' ? 'no-host' : null));
    socket.connect(port, host);
  });
}

// 127.0.0.1 указывает на хост, только когда мы НЕ в контейнере (панель запущена
// прямо на Windows через `node server.js`, см. цикл разработки в CLAUDE.md). Внутри
// контейнера это сам контейнер, и его ECONNREFUSED соврал бы «сервис не слушает» —
// а это готовый неверный диагноз, который увёл бы человека чинить не то. Поэтому
// на обычном Linux-движке Docker (где нет host.docker.internal) честно отвечаем
// «не знаю», а не выдумываем.
const INSIDE_CONTAINER = fs.existsSync('/.dockerenv');

async function hostPortListening(port, proto) {
  if (proto !== 'tcp') return null;
  const candidates = INSIDE_CONTAINER ? HOST_DIAL_CANDIDATES.filter((h) => h !== '127.0.0.1') : HOST_DIAL_CANDIDATES;
  for (const host of candidates) {
    const result = await dialTcp(host, port);
    // 'no-host' — этого имени тут просто нет: пробуем следующего кандидата.
    // Осмысленный ответ (слушает / отказали) отдаём сразу.
    if (result !== 'no-host') return result;
  }
  return null;
}

// Адрес роутера берём точный, с хоста (GATEWAY_IPS, см. get-gateway-ips.ps1): угадывать
// его нельзя, у одного 192.168.1.1, у другого 192.168.0.1, 192.168.31.1 или 10.0.0.1.
// Догадка «последний октет → 1» остаётся только фолбэком для старого start.bat, который
// GATEWAY_IPS ещё не передаёт, и помечается флагом — чтобы в тексте не утверждать лишнего.
function guessGatewayIp(lanIp) {
  const m = /^(\d+\.\d+\.\d+)\.\d+$/.exec(lanIp || '');
  return m ? `${m[1]}.1` : null;
}

// По какой схеме открывается веб-морда роутера: часть отдаёт только https, часть только
// http, кто-то держит её на 8080. Дать заведомо мёртвую ссылку хуже, чем не дать никакой,
// поэтому проверяем дозвоном — панель дотягивается до роутера через NAT хоста, так что
// проверка честная. Таймаут короткий: роутер в одном сегменте, отвечает мгновенно.
// Пустая строка (а не null) на «веб-морды не нашли» — намеренно: memoizeAsync не кэширует
// null/undefined, и отрицательный результат переспрашивался бы на каждой проверке порта.
const gatewayWebUrl = memoizeAsync(async (ip) => {
  if (!ip) return '';
  for (const [port, scheme] of [[80, 'http'], [443, 'https'], [8080, 'http']]) {
    if ((await dialTcp(ip, port, 700)) === true) return `${scheme}://${ip}${port === 80 || port === 443 ? '' : `:${port}`}`;
  }
  return '';
}, 60 * 1000);

// --- Достижимость порта снаружи -------------------------------------------
// Каждый запрос дёргает check-host.net и ждёт его до ~9с (6 опросов по 1.5с) —
// нормальная страница разом шлёт максимум 3 (checkPort() по всем PORTS_TO_CHECK),
// но ничего не мешает клиенту наспамить параллельных запросов и подвесить сервер
// пачкой висящих промисов (или получить у check-host.net бан за flood). Лимит —
// не по времени (это ломало бы штатный параллельный чек трёх портов), а по числу
// одновременных проверок с одного IP.

const reachabilityInFlight = new Map();
const MAX_CONCURRENT_REACHABILITY_PER_IP = 6;

app.get('/api/reachability', async (req, res) => {
  const port = Number(req.query.port) || 5000;
  const proto = req.query.proto === 'tcp' ? 'tcp' : 'udp';

  const clientIp = req.ip;
  const inFlight = reachabilityInFlight.get(clientIp) || 0;
  if (inFlight >= MAX_CONCURRENT_REACHABILITY_PER_IP) {
    return res.status(429).json({ error: 'слишком много одновременных проверок — подожди немного' });
  }
  reachabilityInFlight.set(clientIp, inFlight + 1);
  res.on('finish', () => {
    const left = (reachabilityInFlight.get(clientIp) || 1) - 1;
    if (left <= 0) reachabilityInFlight.delete(clientIp);
    else reachabilityInFlight.set(clientIp, left);
  });

  const localIps = (process.env.HOST_IPS || '').split(',').map((s) => s.trim()).filter(Boolean);
  const localIp = localIps[0];
  // LAN-адрес этой машины — отдельно от HOST_IPS (start.bat кладёт туда ПУБЛИЧНЫЙ адрес,
  // если его удалось узнать, см. комментарий про natLikely ниже). Нужен и для вывода
  // "между тобой и интернетом есть роутер", и для инструкции по пробросу: в правило
  // роутера вписывается именно LAN-адрес, а не публичный.
  const lanIps = (process.env.LAN_IPS || '').split(',').map((s) => s.trim()).filter(Boolean);
  if (!localIp) {
    return res.status(502).json({ error: 'HOST_IPS не задан — запусти ярлык «Запустить трансляцию»' });
  }

  // Для инструкции по роутеру нужен именно приватный адрес, поэтому он и предпочитается;
  // но собственный адрес машины важен и тогда, когда он публичный (сервер на VPS) — по
  // нему видно обратное: NAT'а нет вовсе, значит и пробрасывать нечего, вся оборона на
  // самой машине. Поэтому lanIp — «свой адрес» вообще, а natLikely ниже смотрит, приватный ли он.
  const lanIp = lanIps.find(isPrivateIp) || lanIps[0] || (isPrivateIp(localIp) ? localIp : null);
  const gatewayIps = (process.env.GATEWAY_IPS || '').split(',').map((s) => s.trim()).filter(Boolean);
  const gatewayIp = gatewayIps[0] || guessGatewayIp(lanIp);
  const gatewayGuessed = !gatewayIps[0] && Boolean(gatewayIp);

  // Локальная сторона — считаем всегда, даже если внешняя проверка потом упадёт:
  // "контейнер не запущен" видно и без check-host.net, и это готовый ответ на
  // вопрос "почему закрыт", который не нужно искать в роутере.
  const [container, hostListening, gatewayUrl] = await Promise.all([
    containerPortState(port).catch((e) => ({ dockerError: e.message })),
    hostPortListening(port, proto).catch(() => null),
    gatewayWebUrl(gatewayIp).catch(() => ''),
  ]);
  const local = { ...(container || {}), hostListening };

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
  const freshPublicIp = await fetchPublicIp();
  const targetIp = isPrivateIp(localIp) ? freshPublicIp : localIp;
  if (!targetIp) {
    return res.status(502).json({ localIp, localIps, lanIps, local, error: 'Не удалось узнать внешний IP — проверь интернет' });
  }

  // HOST_IPS снимается ОДИН раз, при запуске ярлыка, и дальше живёт в переменной
  // окружения контейнера. У бытового провайдера адрес динамический и меняется сам
  // (переподключение сессии, перезагрузка роутера) — после этого панель продолжает
  // показывать и проверять старый адрес, порт по нему честно "закрыт", а причина не
  // в порте вообще. Сравниваем зафиксированный адрес с тем, что интернет видит сейчас.
  const ipChanged = Boolean(freshPublicIp && localIp && !isPrivateIp(localIp) && freshPublicIp !== localIp);

  // "Между этой машиной и интернетом есть NAT" — прежняя проверка isPrivateIp(localIp)
  // в самом частом случае давала ЛОЖЬ: у домашнего пользователя start.bat кладёт в
  // HOST_IPS публичный адрес (ipify спрашивается первым), приватным localIp не был
  // почти никогда, natLikely молчал — и человеку за роутером панель советовала
  // "выключи антивирус" вместо единственно нужного проброса порта. Теперь смотрим на
  // реальный LAN-адрес: он приватный, а снаружи нас видно под другим — значит между
  // нами и интернетом кто-то есть.
  const natLikely = Boolean(lanIp) && isPrivateIp(lanIp) && lanIp !== targetIp;
  const { vpnLikely = false, hostingLikely = false, mobileLikely = false, isp = null, reverse = null } =
    (await ipReputation(targetIp)) || {};

  const facts = {
    targetIp, localIp, localIps, lanIp, lanIps, freshPublicIp, ipChanged,
    gatewayIp, gatewayGuessed, gatewayUrl,
    natLikely, vpnLikely, hostingLikely, mobileLikely, isp, reverse,
    port, proto, local,
  };

  try {
    const probe = await probePortExternally(targetIp, port, proto);
    // probePortExternally отдаёт { reachable, verdict, ... } — verdict уже сведён
    // к одному слову (open/refused/timeout/unreachable/mixed), его и кладём в лог.
    logger.log('panel', 'info',
      `проверка порта ${port}/${proto} на ${targetIp}: ${probe.verdict}` +
      `${natLikely ? ', похоже на NAT' : ''}${vpnLikely ? ', похоже на VPN' : ''}`);
    res.json({ ...facts, ...probe });
  } catch (e) {
    logger.log('panel', 'warn', `проверка порта ${port}/${proto} на ${targetIp} не удалась: ${e.message}`);
    res.status(502).json({ ...facts, error: e.message });
  }
});

// Ошибки в браузере панели — единственный класс поломок, которого сейчас не видно
// нигде: «у меня просто белая страница» не оставляет следа ни в docker logs, ни в
// логе панели. Маршрут за общей авторизацией (app.use(auth) выше).
let clientErrorsThisMinute = 0;
setInterval(() => { clientErrorsThisMinute = 0; }, 60 * 1000).unref();

app.post('/api/logs/client', (req, res) => {
  // Зацикленная ошибка в браузере (ошибка в обработчике ошибок) залила бы файл
  // за секунды — ограничиваем и по частоте, и по длине.
  if (clientErrorsThisMinute >= 20) return res.json({ ok: true, dropped: true });
  clientErrorsThisMinute += 1;
  const body = req.body || {};
  const message = String(body.message || '').slice(0, 2000).replace(/\s+/g, ' ');
  const where = String(body.url || '').slice(0, 200);
  const stack = String(body.stack || '').slice(0, 2000).replace(/\s+/g, ' ');
  if (message) logger.log('ui', 'error', `${message}${where ? ` @ ${where}` : ''}${stack ? ` | ${stack}` : ''}`);
  res.json({ ok: true });
});

// Выгрузка всего, что нужно постороннему человеку, чтобы понять, почему у
// пользователя не работает. Один .txt, а не zip: не нужна новая зависимость,
// а текстовый файл проще переслать в мессенджер и открыть чем угодно.
// Имена именно контейнеров, а не сервисов compose: у панели container_name
// stream-panel, и по 'panel' docker её не находит.
const BUNDLE_SERVICES = ['srs', 'srtla-rec', 'stream-panel', 'overlay', CAPTIONS_CONTAINER];

// Пароль панели и токен статистики в выгрузку попасть не должны — файл пересылают
// посторонним. Показываем только сам факт, что значение задано.
const shown = (value) => (value ? '(задан)' : '(пусто)');

// Без follow докер отдаёт лог одним буфером, а не потоком, и demuxStream к нему
// не применить. Кадры при этом те же: 8 байт заголовка (номер потока + длина),
// затем данные. Без разбора заголовков в текст лезут управляющие символы.
function demuxDockerBuffer(buf) {
  const out = [];
  let i = 0;
  while (i + 8 <= buf.length) {
    const len = buf.readUInt32BE(i + 4);
    if (len <= 0 || i + 8 + len > buf.length) break;
    out.push(buf.slice(i + 8, i + 8 + len).toString('utf8'));
    i += 8 + len;
  }
  // Контейнер, запущенный с TTY, отдаёт поток вообще без заголовков — тогда как есть.
  return out.length ? out.join('') : buf.toString('utf8');
}

async function bundleHeader() {
  const lines = [];
  lines.push('=== Bondcast — выгрузка логов ===');
  lines.push(`Собрано: ${new Date(Date.now() + (Number(process.env.TZ_OFFSET_MIN) || 0) * 60000).toISOString().replace('T', ' ').slice(0, 19)} (местное время хоста)`);
  try {
    lines.push(`Версия установки: ${fs.readFileSync('/build-context/VERSION', 'utf8').trim()}`);
  } catch (e) {
    lines.push('Версия установки: неизвестна (файл VERSION не смонтирован)');
  }
  try {
    const v = await docker.version();
    lines.push(`Docker: ${v.Version} (API ${v.ApiVersion}, ${v.Os}/${v.Arch})`);
  } catch (e) {
    lines.push(`Docker: недоступен — ${e.message}`);
  }
  lines.push(`HOST_IPS: ${process.env.HOST_IPS || '(пусто)'}`);
  lines.push(`LAN_IPS: ${process.env.LAN_IPS || '(пусто)'}`);
  lines.push(`GATEWAY_IPS: ${process.env.GATEWAY_IPS || '(пусто)'}`);
  lines.push(`PANEL_USER: ${shown(process.env.PANEL_USER)}  PANEL_PASS: ${shown(process.env.PANEL_PASS)}  NOALBS_STATS_TOKEN: ${shown(process.env.NOALBS_STATS_TOKEN)}`);
  lines.push('');
  lines.push('--- Контейнеры ---');
  for (const name of BUNDLE_SERVICES) {
    try {
      const info = await docker.getContainer(name).inspect();
      lines.push(`${name}: ${info.State.Status}, запущен ${info.State.StartedAt}, перезапусков ${info.RestartCount}`);
    } catch (e) {
      lines.push(`${name}: не найден`);
    }
  }
  return lines.join('\n');
}

app.get('/api/logs/bundle', async (req, res) => {
  // Сводку про накопленные повторы выталкиваем прямо сейчас, иначе последние
  // события эфира не попадут в файл, который человек скачивает именно из-за них.
  logger.flush();

  const parts = [];
  try {
    parts.push(await bundleHeader());
  } catch (e) {
    parts.push(`=== Bondcast — выгрузка логов ===\nШапку собрать не удалось: ${e.message}`);
  }

  for (const file of logger.readFiles()) {
    parts.push(`\n\n=== ${file.name} ===\n${file.content}`);
  }

  // Хвосты напрямую из docker — страховка: панель могла стартовать позже сервисов
  // или коллектор мог отвалиться, и тогда в нашем файле нужных строк просто нет.
  for (const name of BUNDLE_SERVICES) {
    try {
      const raw = await docker.getContainer(name).logs({ stdout: true, stderr: true, tail: 500, timestamps: false });
      parts.push(`\n\n=== docker logs ${name} (последние 500 строк) ===\n${demuxDockerBuffer(raw)}`);
    } catch (e) {
      parts.push(`\n\n=== docker logs ${name} ===\nнедоступно: ${e.message}`);
    }
  }

  const stamp = new Date(Date.now() + (Number(process.env.TZ_OFFSET_MIN) || 0) * 60000)
    .toISOString().slice(0, 16).replace('T', '-').replace(':', '');
  res.set('Content-Type', 'text/plain; charset=utf-8');
  // Имя файла латиницей: русские буквы в Content-Disposition требуют RFC 5987,
  // и часть мессенджеров всё равно ломает такое имя при пересылке.
  res.set('Content-Disposition', `attachment; filename="bondcast-logs-${stamp}.txt"`);
  res.send(parts.join(''));
  logger.log('panel', 'info', 'логи выгружены в файл');
});

// Живой лог для вкладки «Диагностика». Читаем из кольцевого буфера, а не из
// файла: файл ротируется и может быть недоступен, а буфер есть всегда.
app.get('/api/logs/stream', (req, res) => {
  res.set({
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  res.flushHeaders();

  for (const line of logger.tail(500)) res.write(`data: ${line}\n\n`);

  const onLine = (line) => res.write(`data: ${line}\n\n`);
  logger.subscribe(onLine);

  // Тот же приём, что у /api/containers/:name/logs ниже — прокси и браузеры
  // рвут молчащее SSE-соединение.
  const keepAlive = setInterval(() => res.write(':keep-alive\n\n'), 15000);

  req.on('close', () => {
    clearInterval(keepAlive);
    logger.unsubscribe(onLine);
  });
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
    logger.log('panel', 'info', `запуск контейнера ${req.params.name}: ок`);
    res.json({ ok: true });
  } catch (e) {
    logger.log('panel', 'error', `запуск контейнера ${req.params.name}: ${e.message}`);
    res.status(500).json({ error: e.message });
  }
});

app.post('/api/containers/:name/recreate', checkAllowed, async (req, res) => {
  const name = req.params.name;
  if (!PROJECT_ROOT) {
    logger.log('panel', 'error', 'пересоздание невозможно: PROJECT_ROOT не задан');
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
    logger.log('panel', 'info', `пересоздание контейнера ${name}: ок`);
    res.json({ ok: true });
  } catch (e) {
    logger.log('panel', 'error', `пересоздание контейнера ${name}: ${e.message}`);
    res.status(500).json({ error: e.message });
  }
});

app.post('/api/containers/:name/stop', checkAllowed, async (req, res) => {
  try {
    await docker.getContainer(req.params.name).stop();
    logger.log('panel', 'info', `остановка контейнера ${req.params.name}: ок`);
    res.json({ ok: true });
  } catch (e) {
    logger.log('panel', 'error', `остановка контейнера ${req.params.name}: ${e.message}`);
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

app.get('/api/update/status', (req, res) => {
  const currentVersion = readCurrentVersion();
  const latestVersion = latestVersionCache.version;
  res.json({
    currentVersion,
    latestVersion,
    note: latestVersionCache.note,
    updateAvailable: Boolean(latestVersion) && isVersionNewer(latestVersion, currentVersion),
    checkedAt: latestVersionCache.checkedAt,
  });
});

app.get('/api/update/progress', (req, res) => {
  res.json(updateProgress);
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
  minBitrateKbps: 0, // 0 — не проверять, переключать только при полном пропадании паблиша
  state: 'idle', // idle (выключен) | watching (включён, ждёт) | switched (сейчас на резервной сцене)
  lastError: null,
};
let rememberedLiveScene = null; // сцена, на которую вернёмся, когда сигнал появится снова
let pendingSwitchTimer = null;
let watchedStreamWasLive = null; // null — ещё не знаем (только включили/сменили стрим); дальше true/false для детекта фронта
let switchBackInFlight = false; // не плодим параллельные возвраты, если OBS отвечает дольше тика

// Короткое окно битрейта для решения «сигнал вернулся». recv_30s из SRS после
// переподключения ещё полминуты тянет в себе нули с момента обрыва, а телефонный
// ABR разгоняется постепенно — по нему возврат сцены затягивался на минуту и
// дольше. Считаем сами по приросту recv_bytes за последние ~6с.
const RETURN_WINDOW_MS = 6000;
let recvSamples = []; // [{ t, bytes }] отслеживаемого стрима

function trackRecvBytes(watched) {
  if (!watched || watched.recvBytes == null) {
    recvSamples = [];
    return;
  }
  const now = Date.now();
  const last = recvSamples[recvSamples.length - 1];
  if (last && watched.recvBytes < last.bytes) recvSamples = []; // счётчик сбросился — новый паблиш
  recvSamples.push({ t: now, bytes: watched.recvBytes });
  while (recvSamples.length > 2 && now - recvSamples[1].t >= RETURN_WINDOW_MS) recvSamples.shift();
}

// null — данных на окно ещё не набралось
function shortWindowKbps() {
  if (recvSamples.length < 2) return null;
  const first = recvSamples[0];
  const last = recvSamples[recvSamples.length - 1];
  if (last.t - first.t < RETURN_WINDOW_MS / 2) return null;
  return ((last.bytes - first.bytes) * 8) / (last.t - first.t); // байт/мс → кбит/с
}

// obs-websocket-js не ограничивает время ответа: на «зависшем» сокете вызов
// не завершится никогда, и возврат сцены молча пропадёт. Рвём соединение по
// таймауту — следующий вызов переподключится через ensureObsConnected.
const OBS_CALL_TIMEOUT_MS = 5000;

async function obsCall(requestType, requestData) {
  const client = await ensureObsConnected();
  let timer;
  try {
    return await Promise.race([
      client.call(requestType, requestData),
      new Promise((_, reject) => {
        timer = setTimeout(() => {
          obs.disconnect().catch(() => {});
          reject(new Error(`OBS не ответил на ${requestType} за ${OBS_CALL_TIMEOUT_MS / 1000} с`));
        }, OBS_CALL_TIMEOUT_MS);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

function clearPendingSwitch() {
  if (pendingSwitchTimer) {
    clearTimeout(pendingSwitchTimer);
    pendingSwitchTimer = null;
  }
}

async function switchToFallback() {
  pendingSwitchTimer = null;
  try {
    const current = await obsCall('GetCurrentProgramScene');
    // Уже стоим на заглушке (переключили руками или прошлый цикл) — не затираем
    // запомненную рабочую сцену, иначе «вернём» на ту же заглушку.
    if (current.sceneName !== sceneSwitcher.fallbackScene) rememberedLiveScene = current.sceneName;
    await obsCall('SetCurrentProgramScene', { sceneName: sceneSwitcher.fallbackScene });
    sceneSwitcher.state = 'switched';
    sceneSwitcher.lastError = null;
    logger.log('panel', 'warn', `сцены: сигнал "${sceneSwitcher.watchStreamName}" пропал — включена заглушка "${sceneSwitcher.fallbackScene}" (вернём на "${rememberedLiveScene}")`);
  } catch (e) {
    sceneSwitcher.lastError = e.message;
    logger.log('panel', 'error', `сцены: не удалось включить заглушку: ${e.message}`);
  }
}

async function switchBackToLive() {
  if (switchBackInFlight) return;
  switchBackInFlight = true;
  try {
    if (rememberedLiveScene) {
      await obsCall('SetCurrentProgramScene', { sceneName: rememberedLiveScene });
    }
    sceneSwitcher.state = 'watching';
    sceneSwitcher.lastError = null;
    logger.log('panel', 'info', `сцены: сигнал вернулся — снова "${rememberedLiveScene ?? '(сцена не запомнена)'}"`);
  } catch (e) {
    sceneSwitcher.lastError = e.message;
    logger.log('panel', 'error', `сцены: не удалось вернуть рабочую сцену: ${e.message}`);
  } finally {
    switchBackInFlight = false;
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

  // monitorTick крутится раз в 2 секунды — логируем только переходы, а не каждый тик.
  const liveNames = new Set(streams.map((s) => s.name));
  for (const name of liveNames) {
    if (!streamFirstSeenAt.has(name)) {
      streamFirstSeenAt.set(name, Date.now());
      logger.log('panel', 'info', `в эфире появился поток "${name}"`);
    }
  }
  for (const name of [...streamFirstSeenAt.keys()]) {
    if (!liveNames.has(name)) {
      const sec = Math.round((Date.now() - streamFirstSeenAt.get(name)) / 1000);
      streamFirstSeenAt.delete(name);
      logger.log('panel', 'warn', `поток "${name}" пропал, был в эфире ${sec} с`);
    }
  }

  if (!sceneSwitcher.enabled || !sceneSwitcher.watchStreamName || !sceneSwitcher.fallbackScene) return;
  const watched = streams.find((s) => s.name === sceneSwitcher.watchStreamName);
  // minBitrateKbps=0 — прежнее поведение (только публикует/нет). С порогом канал
  // формально в эфире, но битрейта в нём уже недостаточно, тоже считаем "не живым" —
  // kbpsRecv30s ещё null первые секунды после коннекта, тогда тоже "недостаточно".
  const bitrateOk = !sceneSwitcher.minBitrateKbps || (watched && watched.kbpsRecv30s != null && watched.kbpsRecv30s >= sceneSwitcher.minBitrateKbps);
  const isLive = Boolean(watched) && bitrateOk;

  // Возврат — по короткому окну (см. trackRecvBytes): на заглушке висим, пока
  // свежий битрейт не дотянет до порога, а не пока 30-секундное среднее отмоется
  // от нулей обрыва. Без порога — достаточно самого факта паблиша.
  trackRecvBytes(watched);
  const recentKbps = shortWindowKbps();
  const isBack = Boolean(watched) && (!sceneSwitcher.minBitrateKbps || (recentKbps != null ? recentKbps >= sceneSwitcher.minBitrateKbps : bitrateOk));

  if (isLive && pendingSwitchTimer) {
    clearPendingSwitch(); // сигнал вернулся раньше, чем истёк delay — переключать не нужно
  } else if (!isLive && watchedStreamWasLive && !pendingSwitchTimer && sceneSwitcher.state !== 'switched') {
    pendingSwitchTimer = setTimeout(switchToFallback, sceneSwitcher.delaySec * 1000);
  } else if (isBack && sceneSwitcher.state === 'switched') {
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
  const rawMinBitrate = Number(body.minBitrateKbps);
  const minBitrateKbps = Number.isFinite(rawMinBitrate) ? Math.min(50000, Math.max(0, Math.round(rawMinBitrate))) : sceneSwitcher.minBitrateKbps;

  if (enabled && (!watchStreamName || !STREAM_NAME_RE.test(watchStreamName))) {
    return res.status(400).json({ error: 'не выбран стрим для отслеживания' });
  }
  if (enabled && !fallbackScene) {
    return res.status(400).json({ error: 'не выбрана резервная сцена' });
  }

  // fallbackScene может быть значением, запомненным с прошлого раза, когда OBS ещё
  // был доступен (sceneSwitcher.fallbackScene переживает выключение, см. ниже) —
  // без этой проверки включить "watching" можно вслепую, а обрыв WS вскрылся бы
  // только в момент реальной пропажи сигнала, когда переключать сцену уже поздно.
  if (enabled) {
    try {
      await ensureObsConnected();
    } catch (e) {
      return res.status(502).json({
        error: 'Не удалось подключиться к OBS — включи WebSocket-сервер (Tools → WebSocket Server Settings, порт 4455) и попробуй снова.',
        // Клиент (app.js) по этому коду показывает не просто текст ошибки, а
        // полную инструкцию (OBS_WEBSOCKET_HOWTO) прямо на месте, вместо
        // "смотри подсказку в другом сценарии".
        code: 'obs_unreachable',
      });
    }
  }

  // Смена отслеживаемого стрима или выключение — сбрасываем текущий цикл
  // переключения, чтобы не словить лишнее переключение сцены на стыке смены настроек.
  if (watchStreamName !== sceneSwitcher.watchStreamName || !enabled) {
    clearPendingSwitch();
    if (sceneSwitcher.state === 'switched') await switchBackToLive();
    watchedStreamWasLive = null;
    rememberedLiveScene = null;
    recvSamples = [];
  }

  // Сменили заглушку прямо во время показа заглушки — сразу показываем новую.
  if (enabled && sceneSwitcher.state === 'switched' && fallbackScene !== sceneSwitcher.fallbackScene) {
    try {
      await obsCall('SetCurrentProgramScene', { sceneName: fallbackScene });
    } catch (e) {
      sceneSwitcher.lastError = e.message;
    }
  }

  sceneSwitcher.enabled = enabled;
  sceneSwitcher.watchStreamName = enabled ? watchStreamName : null;
  sceneSwitcher.fallbackScene = enabled ? fallbackScene : sceneSwitcher.fallbackScene; // помним выбор даже выключенным
  sceneSwitcher.delaySec = delaySec;
  sceneSwitcher.minBitrateKbps = minBitrateKbps;
  // Правка задержки/порога/заглушки во время показа заглушки не должна
  // «забывать», что мы на ней — иначе возврат на рабочую сцену не случится.
  sceneSwitcher.state = !enabled ? 'idle' : sceneSwitcher.state === 'switched' ? 'switched' : 'watching';
  if (enabled && sceneSwitcher.state !== 'switched') sceneSwitcher.lastError = null;

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

// Живой энроллмент голоса (см. /api/captions/enroll) — прогресс/итог живёт в
// памяти процесса так же, как buildStatus у сборки образа: это штучная
// операция на 15-20 секунд, не постоянный поток, переживать перезапуск панели ей
// незачем — единственный переживающий рестарт факт (сами голоса) лежит на
// диске (voices/voices.json + *.npy), читается заново при каждом /status.
let enrollStatus = 'idle'; // idle | running | done | error
let enrollError = null;
// Какой голос сейчас пишется (или только что дописался) — существующий id
// при перезаписи, null при первой записи нового (фронту нужно понимать, чей
// прогресс-бар/инпут имени показывать; настоящий id нового голоса он узнаёт
// из ответа POST /api/captions/enroll, здесь — на случай, если страницу
// обновили посреди записи и voiceId из ответа уже потерян).
let enrollVoiceId = null;

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

function captionsSpec(streamName, asrModel) {
  // Голоса (список + пороги) больше не передаются через Env — asr-worker сам
  // следит за voices.json внутри смонтированной директории и подхватывает
  // правки живьём, без пересоздания контейнера.
  return {
    name: CAPTIONS_CONTAINER,
    Image: CAPTIONS_IMAGE,
    Env: [
      `ASR_OBS_SOURCE_URL=srt://srs:10080?streamid=live/${streamName}`,
      'ASR_OBS_CONFIG=/srv/config.yaml',
      `ASR_OBS_ASR_MODEL=${asrModel}`,
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
        `${hostPath('asr-obs\\voices')}:/srv/voices:ro`,
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

function enrollSpec(streamName, durationSec, voiceId) {
  return {
    name: ENROLL_CONTAINER,
    Image: CAPTIONS_IMAGE,
    Entrypoint: ['python3', '-m', 'app.live_enroll'],
    Cmd: [
      '--source-url', `srt://srs:10080?streamid=live/${streamName}`,
      '--duration', String(durationSec),
      '--out', `/srv/voices/${voiceId}.npy`,
    ],
    Env: ['ASR_OBS_CONFIG=/srv/config.yaml'],
    HostConfig: {
      RestartPolicy: { Name: 'no' },
      Binds: [
        `${hostPath('asr-obs\\config.yaml')}:/srv/config.yaml:ro`,
        // Не :ro — сюда пишем результат энроллмента (весь каталог голосов,
        // не отдельный файл — новому voiceId ещё нет соответствующего .npy
        // на диске, и это нормально, live_enroll.py создаёт его сам).
        `${hostPath('asr-obs\\voices')}:/srv/voices`,
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
  logger.log('panel', 'info', 'началась сборка образа субтитров');

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
  // Имя/цвет каждого голоса больше не в URL — их резолвит живьём сам
  // asr-worker (см. speaker.py) и шлёт по WS, оверлей просто рендерит.
  // Ссылка меняется только от оформления (см. buildOverlayUrl во фронте).
  const overlayUrl = `http://${overlayHost}:${CAPTIONS_OVERLAY_PORT}/index.html`;
  const voices = readVoices().map((v) => ({ ...v, hasEmbedding: hasVoiceEmbedding(v.id) }));
  const common = {
    overlayUrl,
    imageExists: hasImage,
    buildStatus,
    buildError,
    voices,
    enrollStatus,
    enrollError,
    enrollVoiceId,
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
    });
  } catch (e) {
    res.json({ ...common, connected: false, streamName: null, ready: false, asrModel: null });
  }
});

app.post('/api/captions/connect', async (req, res) => {
  const name = String((req.body && req.body.name) || '').trim();
  if (!STREAM_NAME_RE.test(name)) {
    return res.status(400).json({ error: 'некорректное имя стрима' });
  }
  const asrModel = ASR_MODELS.includes(req.body && req.body.asrModel) ? req.body.asrModel : ASR_MODELS[0];
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
    captionsReady = false;
    const container = await docker.createContainer(captionsSpec(name, asrModel));
    await container.start();
    watchCaptionsReadiness(container); // не await — следит в фоне, ответ не блокирует
    logger.log('panel', 'info', `субтитры подключены к потоку "${name}", модель ${asrModel}`);
    res.json({ ok: true, streamName: name });
  } catch (e) {
    logger.log('panel', 'error', `субтитры не подключились к "${name}": ${e.message}`);
    res.status(500).json({ error: e.message });
  }
});

app.post('/api/captions/disconnect', async (req, res) => {
  try {
    await docker.getContainer(CAPTIONS_CONTAINER).remove({ force: true });
    captionsReady = false;
    logger.log('panel', 'info', 'субтитры отключены');
    res.json({ ok: true });
  } catch (e) {
    if (e.statusCode === 404) return res.json({ ok: true });
    logger.log('panel', 'error', `субтитры не отключились: ${e.message}`);
    res.status(500).json({ error: e.message });
  }
});

// Живая запись голоса: короткий одноразовый контейнер слушает указанный живой
// стрим ENROLL_DURATION_SEC секунд, режет речь через VAD и усредняет ECAPA-
// эмбеддинги (app/live_enroll.py — то же самое, что офлайновый app/enroll.py по
// wav-файлам, только источник — сам живой SRT-поток). Без voiceId — запись
// НОВОГО голоса (имя вводится ПОСЛЕ успешной записи, не до — см. app.js); с
// voiceId существующего голоса — перезапись его эталона тем же id/именем/порогом.
app.post('/api/captions/enroll', async (req, res) => {
  const streamName = String((req.body && req.body.streamName) || '').trim();
  const requestedVoiceId = req.body && req.body.voiceId != null ? String(req.body.voiceId).trim() : null;
  if (!STREAM_NAME_RE.test(streamName)) {
    return res.status(400).json({ error: 'некорректное имя стрима' });
  }
  logger.log('panel', 'info', `запись эталона голоса, ${ENROLL_DURATION_SEC} с`);
  const voices = readVoices();
  let voiceId;
  let isNewVoice;
  if (requestedVoiceId) {
    if (!voices.some((v) => v.id === requestedVoiceId)) {
      return res.status(404).json({ error: 'голос не найден' });
    }
    voiceId = requestedVoiceId;
    isNewVoice = false;
  } else {
    voiceId = crypto.randomUUID();
    isNewVoice = true;
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
  enrollVoiceId = voiceId;

  try {
    try {
      await docker.getContainer(ENROLL_CONTAINER).remove({ force: true });
    } catch (e) {
      if (e.statusCode !== 404) throw e;
    }

    await ensureNetwork();
    const container = await docker.createContainer(enrollSpec(streamName, ENROLL_DURATION_SEC, voiceId));
    await container.start();
    res.json({ ok: true, durationSec: ENROLL_DURATION_SEC, voiceId });

    // Не блокируем ответ ожиданием записи (15+ секунд) — статус и логи клиент
    // дальше сам опрашивает/стримит (/api/captions/status, .../logs).
    container
      .wait()
      .then(({ StatusCode }) => {
        if (StatusCode === 0) {
          // Запись в манифест — ТОЛЬКО теперь, после успешного .npy на диске
          // (при перезаписи существующего голоса эмбеддинг уже перезаписан по
          // тому же пути, манифест/имя/порог не трогаем).
          if (isNewVoice) {
            const current = readVoices();
            current.push({ id: voiceId, name: `Голос ${current.length + 1}`, threshold: 0.25 });
            writeVoicesAtomic(current);
          }
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

// Правка имени/порога голоса — лёгкая операция без Docker (только правка
// voices.json), живой mtime-реслав на стороне asr-worker подхватывает её без
// пересоздания контейнера. Белый список полей — не Object.assign(voice,
// req.body): произвольные поля из тела запроса не должны попадать в манифест,
// который Python потом доверчиво читает с диска.
app.patch('/api/captions/voices/:id', (req, res) => {
  const voices = readVoices();
  const voice = voices.find((v) => v.id === req.params.id);
  if (!voice) {
    return res.status(404).json({ error: 'голос не найден' });
  }
  const body = req.body || {};
  if (body.name !== undefined) {
    const name = String(body.name).trim();
    if (!VOICE_NAME_RE.test(name)) {
      return res.status(400).json({ error: 'имя голоса — до 40 символов, без переводов строк, не пустое' });
    }
    voice.name = name;
  }
  if (body.threshold !== undefined) {
    const rawThreshold = Number(body.threshold);
    if (!Number.isFinite(rawThreshold)) {
      return res.status(400).json({ error: 'некорректный порог' });
    }
    voice.threshold = Math.min(1, Math.max(0, rawThreshold));
  }
  writeVoicesAtomic(voices);
  res.json({ ok: true, voice });
});

app.delete('/api/captions/voices/:id', (req, res) => {
  const voices = readVoices();
  const idx = voices.findIndex((v) => v.id === req.params.id);
  if (idx === -1) {
    return res.status(404).json({ error: 'голос не найден' });
  }
  const [removed] = voices.splice(idx, 1);
  writeVoicesAtomic(voices);
  try {
    fs.unlinkSync(voiceEmbeddingPath(removed.id));
  } catch (e) {
    // эталона могло не быть (напр. запись не успела завершиться) — не критично
  }
  res.json({ ok: true });
});

// Правка оформления — та же атомарная запись, тот же принцип "меняем живьём,
// без пересоздания чего-либо": overlay/index.html сам перечитывает файл
// (см. readOverlayStyle/GET выше, публичный), панель просто пишет.
app.patch('/api/captions/overlay-style', (req, res) => {
  const body = req.body || {};
  const next = readOverlayStyle();

  if (body.size !== undefined) {
    const size = Number(body.size);
    if (!Number.isFinite(size)) return res.status(400).json({ error: 'некорректный размер шрифта' });
    next.size = Math.min(72, Math.max(16, Math.round(size)));
  }
  if (body.lines !== undefined) {
    const lines = Number(body.lines);
    if (!Number.isFinite(lines)) return res.status(400).json({ error: 'некорректное число строк' });
    next.lines = Math.min(8, Math.max(1, Math.round(lines)));
  }
  if (body.guestColor !== undefined) {
    if (!OVERLAY_COLOR_RE.test(body.guestColor)) return res.status(400).json({ error: 'некорректный цвет' });
    next.guestColor = body.guestColor;
  }
  if (body.bgColor !== undefined) {
    if (!OVERLAY_COLOR_RE.test(body.bgColor)) return res.status(400).json({ error: 'некорректный цвет' });
    next.bgColor = body.bgColor;
  }
  if (body.bgOpacity !== undefined) {
    const opacity = Number(body.bgOpacity);
    if (!Number.isFinite(opacity)) return res.status(400).json({ error: 'некорректная прозрачность фона' });
    next.bgOpacity = Math.min(100, Math.max(0, Math.round(opacity)));
  }
  if (body.showSpeaker !== undefined) {
    next.showSpeaker = Boolean(body.showSpeaker);
  }

  writeOverlayStyleAtomic(next);
  res.json({ ok: true, style: next });
});

// Обработчик ошибок express должен стоять после всех маршрутов, иначе он их не увидит.
// Ловит то, что не поймали сами обработчики — необработанные исключения в async-роутах
// express 4 сюда не попадают, поэтому это именно сеть безопасности, а не единственный
// источник записей об ошибках.
app.use((err, req, res, next) => {
  logger.log('panel', 'error', `${req.method} ${req.path}: ${err.message}`);
  if (res.headersSent) return next(err);
  res.status(500).json({ error: err.message });
});

const port = process.env.PORT || 8081;
const server = http.createServer(app);

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname !== '/ws-stats') {
    socket.destroy();
    return;
  }
  // Пусто (как и PANEL_USER/PANEL_PASS выше) — токен не требуется, тот же принцип
  // "не задано - фича выключена". Задан - должен совпасть, иначе кто угодно,
  // достучавшийся до панели, получил бы бесплатный доступ к битрейту стрима.
  const requiredToken = process.env.NOALBS_STATS_TOKEN;
  if (requiredToken && url.searchParams.get('token') !== requiredToken) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
});

server.listen(port, () => {
  console.log(`stream-panel listening on :${port}`);
  logger.log('panel', 'info', `панель запущена на :${port}, лог: ${logger.filePath}`);
});
