// Единая точка записи логов панели и всех сервисов стека.
//
// Модуль намеренно ничего не знает про express и dockerode: сюда приходят уже
// готовые строки, отсюда они уходят в файл (для пересылки) и в кольцевой буфер
// (для живого просмотра во вкладке «Диагностика»). Всё, что связано со сбором,
// живёт в server.js.
const fs = require('fs');
const path = require('path');

const LEVELS = { debug: 10, info: 20, warn: 30, error: 40 };

const DEFAULTS = {
  level: 'info',
  maxBytes: 5 * 1024 * 1024,
  keepFiles: 3, // bondcast.log + bondcast.1.log + bondcast.2.log
  ringSize: 2000,
  collapseMs: 60 * 1000,
  tzOffsetMin: 0,
};

// Панель живёт в Linux-контейнере, там время всегда UTC, а лог читает человек,
// который стримил по своим часам. Смещение приезжает с хоста (TZ_OFFSET_MIN,
// его считает start.bat) — иначе в присланном логе время не сойдётся ни с чем.
function formatTime(ms, tzOffsetMin) {
  const d = new Date(ms + tzOffsetMin * 60 * 1000);
  const p = (n, w = 2) => String(n).padStart(w, '0');
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())} ` +
    `${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}.${p(d.getUTCMilliseconds(), 3)}`;
}

// Колонки фиксированной ширины: по такому логу читают глазами и грепают, а
// «asr-worker» — самое длинное имя сервиса, под него и равняем.
function formatLine(ms, tzOffsetMin, service, level, message) {
  return `${formatTime(ms, tzOffsetMin)}  ${level.toUpperCase().padEnd(5)}  ${String(service).padEnd(10)} ${message}`;
}

// Строки, которые не несут информации при расследовании. Каждая — с причиной:
// без неё через полгода никто не рискнёт тронуть регулярку.
const NOISE = [
  // SRS: телеметрия процесса каждые 5 секунд, идёт даже когда эфира нет вообще.
  // Это главный источник мусора в логе — десятки тысяч строк в сутки на пустом стеке.
  /Hybrid cpu=/,
  // SRS: та же периодичность, но статистика по одному соединению.
  /<- (CPB|PLA|SRT) time=/,
  // srtla_rec: подтверждение живости от каждого соединения, несколько раз в секунду.
  /keepalive/i,
  // nginx оверлея: успешная отдача статики. Ошибки (4xx/5xx) под это не подпадают.
  /"(GET|HEAD) [^"]*" (200|204|304) /,
];

function isNoise(message) {
  return NOISE.some((re) => re.test(message));
}

// Повтор — это «то же самое сообщение с другими числами»: счётчики пакетов,
// номера соединений и таймстемпы внутри строки меняются, смысл нет.
function normalize(message) {
  return message.replace(/\d+/g, '#');
}

function humanDuration(ms) {
  const sec = Math.round(ms / 1000);
  if (sec < 60) return `${sec}с`;
  return `${Math.floor(sec / 60)}м${String(sec % 60).padStart(2, '0')}с`;
}

function createLogger(options = {}) {
  const opts = { ...DEFAULTS, ...options };
  const now = opts.now || Date.now;
  const dir = opts.dir;
  const filePath = path.join(dir, 'bondcast.log');
  const threshold = LEVELS[opts.level] || LEVELS.info;

  const ring = [];
  const subscribers = new Set();
  let size = 0;
  let fileBroken = false; // ругаемся в stdout один раз, дальше молча живём без файла
  // Накопитель повторов: первую строку пишем сразу (ошибку надо видеть немедленно),
  // а её повторы копим и выдаём одной сводкой.
  let pending = null; // { key, service, level, firstMs, lastMs, count }

  try {
    fs.mkdirSync(dir, { recursive: true });
    size = fs.statSync(filePath).size;
  } catch (e) {
    if (e.code !== 'ENOENT') markBroken(e);
  }

  function markBroken(e) {
    if (fileBroken) return;
    fileBroken = true;
    // Именно console.error: логгер сломан, писать об этом через него же нельзя.
    console.error(`[logger] не могу писать в ${filePath}: ${e.message}. Лог остаётся только в памяти.`);
  }

  function rotate() {
    try {
      // Сдвигаем хвост с конца, иначе перезапишем ещё не сдвинутый файл.
      for (let i = opts.keepFiles - 1; i >= 1; i--) {
        const from = i === 1 ? filePath : path.join(dir, `bondcast.${i - 1}.log`);
        const to = path.join(dir, `bondcast.${i}.log`);
        if (fs.existsSync(from)) {
          if (i === opts.keepFiles - 1 && fs.existsSync(to)) fs.unlinkSync(to);
          fs.renameSync(from, to);
        }
      }
      size = 0;
    } catch (e) {
      // Файл держит антивирус/проводник — продолжаем писать в текущий, чтобы не
      // зациклиться на ротации при каждой следующей строке.
      markBroken(e);
    }
  }

  function writeLine(line) {
    ring.push(line);
    if (ring.length > opts.ringSize) ring.shift();
    for (const fn of subscribers) {
      try { fn(line); } catch (e) { /* подписчик отвалился — не наша забота */ }
    }
    if (fileBroken) return;
    const buf = Buffer.byteLength(line, 'utf8') + 1;
    try {
      if (size + buf > opts.maxBytes) rotate();
      fs.appendFileSync(filePath, line + '\n');
      size += buf;
    } catch (e) {
      markBroken(e);
    }
  }

  function emit(ms, service, level, message) {
    writeLine(formatLine(ms, opts.tzOffsetMin, service, level, message));
  }

  function flushPending() {
    if (!pending || pending.count === 0) {
      pending = null;
      return;
    }
    emit(
      pending.lastMs,
      pending.service,
      pending.level,
      `↑ повторилось ещё ×${pending.count} за ${humanDuration(pending.lastMs - pending.firstMs)}`
    );
    pending = null;
  }

  function log(service, level, message) {
    if ((LEVELS[level] || LEVELS.info) < threshold) return;
    const text = String(message).replace(/\s+$/, '');
    if (!text || isNoise(text)) return;

    const ms = now();
    const key = `${service} ${normalize(text)}`;

    if (pending && pending.key === key) {
      pending.count += 1;
      pending.lastMs = ms;
      // Окно истекло — не держим сводку до бесконечности, если строка сыплется часами.
      if (ms - pending.firstMs >= opts.collapseMs) flushPending();
      return;
    }

    flushPending();
    emit(ms, service, level, text);
    pending = { key, service, level, firstMs: ms, lastMs: ms, count: 0 };
  }

  function readFiles() {
    const out = [];
    const prev = path.join(dir, 'bondcast.1.log');
    let currentSize = 0;
    try { currentSize = fs.statSync(filePath).size; } catch (e) { currentSize = 0; }
    // Сразу после ротации текущий файл почти пуст — без предыдущего в присланной
    // выгрузке не окажется как раз того часа, ради которого её и просили.
    if (currentSize < 1024 * 1024 && fs.existsSync(prev)) {
      out.push({ name: 'bondcast.1.log', content: fs.readFileSync(prev, 'utf8') });
    }
    if (fs.existsSync(filePath)) {
      out.push({ name: 'bondcast.log', content: fs.readFileSync(filePath, 'utf8') });
    }
    return out;
  }

  // Без таймера сводка про повторы висела бы в памяти до следующей ДРУГОЙ строки —
  // а её может не быть часами, и как раз в этот момент человек скачивает лог.
  if (!opts.now) {
    const ticker = setInterval(() => {
      if (pending && pending.count > 0 && Date.now() - pending.firstMs >= opts.collapseMs) flushPending();
    }, 5000);
    ticker.unref(); // таймер не должен держать процесс живым сам по себе
  }

  return {
    filePath,
    log,
    tail: (n) => (n ? ring.slice(-n) : ring.slice()),
    subscribe: (fn) => subscribers.add(fn),
    unsubscribe: (fn) => subscribers.delete(fn),
    readFiles,
    flush: flushPending,
  };
}

module.exports = { createLogger, formatLine, isNoise, normalize, LEVELS };
