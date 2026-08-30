# Логи сервера — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Панель собирает логи всех сервисов стека в один файл на диске Windows, режет повторяющийся шум и отдаёт всё одним `.txt` для пересылки.

**Architecture:** Панель — единый коллектор: подписывается на docker-логи `srs`/`srtla-rec`/`overlay`/`asr-worker`, прогоняет строки через чёрный список и схлопывание повторов и пишет их вместе со своими событиями в `logs/bondcast.log` (bind-монтирован с хоста). Тот же поток идёт в кольцевой буфер в памяти — из него живёт SSE для скрытой вкладки «Диагностика». Выгрузка склеивает шапку состояния, файлы лога и хвосты докеровских логов в один текстовый файл.

**Tech Stack:** Node.js 20 (образ `node:20-alpine`), express 4, dockerode 4, встроенный тест-раннер `node --test` (новых зависимостей не добавляем), ванильный JS во фронте.

**Spec:** `docs/logging-design.md`

## Global Constraints

- Рабочая директория для всех команд — `server/stream` (если не сказано иное).
- Новых npm-зависимостей не добавлять. Тесты — только встроенный `node --test`.
- Комментарии в коде и тексты интерфейса — на русском. Коммиты — на русском, 2–10 слов.
- Уровни лога ровно четыре: `debug`, `info`, `warn`, `error`. По умолчанию `info`.
- Ротация: файл `bondcast.log`, лимит 5 МБ, всего максимум 3 файла (`bondcast.log`, `bondcast.1.log`, `bondcast.2.log`).
- Кольцевой буфер — 2000 строк. Окно схлопывания повторов — 60 секунд.
- В выгрузку **никогда** не попадают значения `PANEL_USER`, `PANEL_PASS`, `NOALBS_STATS_TOKEN` — только `(задан)` / `(пусто)`.
- Отсутствие или недоступность папки логов не должны ронять панель.
- Частые опросы фронта (`/api/streams`, `/api/status`, `/api/captions/status`, `/api/update/*`) не логируются ни на каком уровне.

---

### Task 1: Ядро logger.js — формат, уровни, файл, ротация, кольцевой буфер

**Files:**
- Create: `server/stream/panel/logger.js`
- Test: `server/stream/panel/logger.test.js`

**Interfaces:**
- Consumes: ничего.
- Produces: `createLogger(options) -> logger`, где
  - `options = { dir: string, level?: 'debug'|'info'|'warn'|'error', maxBytes?: number, ringSize?: number, collapseMs?: number, tzOffsetMin?: number, now?: () => number }`
  - `logger.log(service: string, level: string, message: string): void`
  - `logger.tail(n?: number): string[]` — последние строки из кольцевого буфера
  - `logger.subscribe(fn: (line: string) => void): void`
  - `logger.unsubscribe(fn): void`
  - `logger.readFiles(): Array<{ name: string, content: string }>`
  - `logger.flush(): void` — вытолкнуть накопленную сводку повторов (Task 2; в этой задаче — пустышка)
  - `logger.filePath: string` — абсолютный путь к текущему `bondcast.log`

- [ ] **Step 1: Написать падающий тест**

Создать `server/stream/panel/logger.test.js`:

```js
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { createLogger } = require('./logger');

function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'bondcast-log-'));
}

test('пишет строку в файл в едином формате', () => {
  const dir = tmpDir();
  // Фиксированное время, чтобы проверять формат строки целиком, а не «примерно».
  const logger = createLogger({ dir, now: () => Date.UTC(2026, 7, 30, 14, 3, 11, 482), tzOffsetMin: 0 });
  logger.log('srs', 'warn', 'SRT connect failed');
  const content = fs.readFileSync(logger.filePath, 'utf8');
  // Собрано по кускам, а не одной строкой: пробелы здесь значимы (уровень дополнен
  // до 5 символов, сервис до 10), и посчитать их глазами в сплошной строке нельзя.
  const expected = '2026-08-30 14:03:11.482' + '  ' + 'WARN ' + '  ' + 'srs' + '       ' + ' ' + 'SRT connect failed\n';
  assert.strictEqual(content, expected);
});

test('уровень ниже порога отбрасывается', () => {
  const dir = tmpDir();
  const logger = createLogger({ dir, level: 'info' });
  logger.log('panel', 'debug', 'подробность');
  logger.log('panel', 'info', 'событие');
  const lines = fs.readFileSync(logger.filePath, 'utf8').trim().split('\n');
  assert.strictEqual(lines.length, 1);
  assert.ok(lines[0].includes('событие'));
});

test('tzOffsetMin сдвигает время в местное', () => {
  const dir = tmpDir();
  const logger = createLogger({ dir, now: () => Date.UTC(2026, 7, 30, 14, 0, 0, 0), tzOffsetMin: 180 });
  logger.log('panel', 'info', 'x');
  assert.ok(fs.readFileSync(logger.filePath, 'utf8').startsWith('2026-08-30 17:00:00.000'));
});

test('кольцевой буфер отдаёт последние строки и не растёт бесконечно', () => {
  const dir = tmpDir();
  const logger = createLogger({ dir, ringSize: 3 });
  // Слова, а не номера: строки, отличающиеся только числом, схлопнулись бы фильтром
  // из Task 2, и в буфере оказалась бы одна запись вместо пяти.
  for (const w of ['раз', 'два', 'три', 'четыре', 'пять']) logger.log('panel', 'info', `строка ${w}`);
  const tail = logger.tail();
  assert.strictEqual(tail.length, 3);
  assert.ok(tail[0].includes('строка три'));
  assert.ok(tail[2].includes('строка пять'));
});

test('подписчик получает каждую записанную строку', () => {
  const dir = tmpDir();
  const logger = createLogger({ dir });
  const got = [];
  const fn = (line) => got.push(line);
  logger.subscribe(fn);
  logger.log('panel', 'info', 'раз');
  logger.unsubscribe(fn);
  logger.log('panel', 'info', 'два');
  assert.strictEqual(got.length, 1);
  assert.ok(got[0].includes('раз'));
});

test('ротация: при превышении лимита появляется bondcast.1.log, файлов не больше трёх', () => {
  const dir = tmpDir();
  const logger = createLogger({ dir, maxBytes: 200 });
  // Текст чередуется по той же причине: одинаковые строки схлопнутся в одну,
  // и файл не дорастёт до ротации.
  for (let i = 0; i < 40; i++) logger.log('panel', 'info', `строка ${i % 2 ? 'чётная' : 'нечётная'} с добивкой до заметной длины`);
  const files = fs.readdirSync(dir).sort();
  assert.deepStrictEqual(files, ['bondcast.1.log', 'bondcast.2.log', 'bondcast.log']);
});

test('readFiles отдаёт текущий файл, а при коротком текущем — ещё и предыдущий', () => {
  const dir = tmpDir();
  const logger = createLogger({ dir, maxBytes: 200 });
  // Текст чередуется по той же причине: одинаковые строки схлопнутся в одну,
  // и файл не дорастёт до ротации.
  for (let i = 0; i < 40; i++) logger.log('panel', 'info', `строка ${i % 2 ? 'чётная' : 'нечётная'} с добивкой до заметной длины`);
  const files = logger.readFiles();
  assert.strictEqual(files.length, 2);
  assert.strictEqual(files[0].name, 'bondcast.1.log');
  assert.strictEqual(files[1].name, 'bondcast.log');
});

test('недоступная папка не роняет логгер', () => {
  // Путь внутри файла — создать директорию по нему невозможно.
  const dir = tmpDir();
  const busy = path.join(dir, 'file.txt');
  fs.writeFileSync(busy, 'x');
  const logger = createLogger({ dir: path.join(busy, 'logs') });
  assert.doesNotThrow(() => logger.log('panel', 'error', 'что-то'));
  assert.ok(logger.tail().length === 1); // в память записалось, несмотря на отсутствие файла
});
```

- [ ] **Step 2: Убедиться, что тест падает**

Выполнить из `server/stream/panel`:

```
node --test logger.test.js
```

Ожидается: FAIL, `Cannot find module './logger'`.

- [ ] **Step 3: Написать logger.js**

Создать `server/stream/panel/logger.js`:

```js
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

  function log(service, level, message) {
    if ((LEVELS[level] || LEVELS.info) < threshold) return;
    emit(now(), service, level, String(message).replace(/\s+$/, ''));
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

  return {
    filePath,
    log,
    tail: (n) => (n ? ring.slice(-n) : ring.slice()),
    subscribe: (fn) => subscribers.add(fn),
    unsubscribe: (fn) => subscribers.delete(fn),
    readFiles,
    flush: () => {}, // наполняется в Task 2 (схлопывание повторов)
  };
}

module.exports = { createLogger, formatLine, LEVELS };
```

- [ ] **Step 4: Убедиться, что тесты проходят**

```
node --test logger.test.js
```

Ожидается: PASS, 8 тестов.

- [ ] **Step 5: Коммит**

```bash
git add server/stream/panel/logger.js server/stream/panel/logger.test.js
git commit -m "логгер панели: формат, ротация, кольцевой буфер"
```

---

### Task 2: Фильтр — чёрный список шума и схлопывание повторов

**Files:**
- Modify: `server/stream/panel/logger.js`
- Test: `server/stream/panel/logger.test.js`

**Interfaces:**
- Consumes: `createLogger` из Task 1.
- Produces:
  - `logger.log()` теперь молча отбрасывает шум и схлопывает повторы;
  - `logger.flush(): void` выталкивает накопленную сводку;
  - экспорт `isNoise(message: string): boolean` и `normalize(message: string): string` — для тестов.

- [ ] **Step 1: Написать падающие тесты**

Дописать в конец `server/stream/panel/logger.test.js`:

```js
const { isNoise, normalize } = require('./logger');

test('строка из чёрного списка не попадает в лог', () => {
  const dir = tmpDir();
  const logger = createLogger({ dir });
  logger.log('srs', 'info', '[2026-08-30 14:00:00.000][Trace][1][abc] Hybrid cpu=0.50%,3MB, timer=100,0,0');
  assert.strictEqual(logger.tail().length, 0);
});

test('обычная строка того же сервиса проходит', () => {
  const dir = tmpDir();
  const logger = createLogger({ dir });
  logger.log('srs', 'info', 'RTMP client ip=172.18.0.1, publish stream=live/foo');
  assert.strictEqual(logger.tail().length, 1);
});

test('нормализация считает строки, отличающиеся числами, одинаковыми', () => {
  assert.strictEqual(normalize('recv 1024 bytes'), normalize('recv 77 bytes'));
  assert.notStrictEqual(normalize('recv bytes'), normalize('send bytes'));
});

test('три одинаковых строки подряд дают одну запись и сводку про два повтора', () => {
  const dir = tmpDir();
  let t = Date.UTC(2026, 7, 30, 14, 0, 0, 0);
  const logger = createLogger({ dir, now: () => t });
  logger.log('srtla-rec', 'warn', 'connection 1 timed out');
  t += 1000; logger.log('srtla-rec', 'warn', 'connection 2 timed out');
  t += 1000; logger.log('srtla-rec', 'warn', 'connection 3 timed out');
  logger.flush();
  const lines = logger.tail();
  assert.strictEqual(lines.length, 2);
  assert.ok(lines[0].includes('connection 1 timed out'));
  assert.ok(lines[1].includes('×2'));
});

test('другая строка выталкивает накопленную сводку перед собой', () => {
  const dir = tmpDir();
  let t = Date.UTC(2026, 7, 30, 14, 0, 0, 0);
  const logger = createLogger({ dir, now: () => t });
  logger.log('srs', 'warn', 'timeout 1');
  t += 1000; logger.log('srs', 'warn', 'timeout 2');
  t += 1000; logger.log('srs', 'error', 'совсем другое');
  const lines = logger.tail();
  assert.strictEqual(lines.length, 3);
  assert.ok(lines[1].includes('×1'));
  assert.ok(lines[2].includes('совсем другое'));
});

test('после окна схлопывания сводка выходит сама, счётчик обнуляется', () => {
  const dir = tmpDir();
  let t = Date.UTC(2026, 7, 30, 14, 0, 0, 0);
  const logger = createLogger({ dir, collapseMs: 60000, now: () => t });
  logger.log('srs', 'warn', 'timeout 1');
  t += 30000; logger.log('srs', 'warn', 'timeout 2');
  t += 40000; logger.log('srs', 'warn', 'timeout 3'); // прошло больше 60 с с первой
  const lines = logger.tail();
  assert.strictEqual(lines.length, 2);
  assert.ok(lines[1].includes('×2'));
});

test('одинаковые строки разных сервисов не схлопываются друг с другом', () => {
  const dir = tmpDir();
  const logger = createLogger({ dir });
  logger.log('srs', 'warn', 'timeout');
  logger.log('srtla-rec', 'warn', 'timeout');
  assert.strictEqual(logger.tail().length, 2);
});

test('isNoise узнаёт keepalive srtla и статику оверлея', () => {
  assert.ok(isNoise('got keepalive from 1.2.3.4'));
  assert.ok(isNoise('172.18.0.1 - - [30/Aug/2026:14:00:00 +0000] "GET /app.js HTTP/1.1" 200 4096 "-" "Mozilla"'));
  assert.ok(!isNoise('172.18.0.1 - - [30/Aug/2026:14:00:00 +0000] "GET /app.js HTTP/1.1" 500 0 "-" "Mozilla"'));
});
```

- [ ] **Step 2: Убедиться, что тесты падают**

```
node --test logger.test.js
```

Ожидается: FAIL — `isNoise is not a function`, и тесты про схлопывание падают (строк больше, чем ожидается).

- [ ] **Step 3: Добавить фильтр в logger.js**

В `server/stream/panel/logger.js` перед `function createLogger` вставить:

```js
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
```

Внутри `createLogger`, сразу после объявления `let fileBroken = false;`, добавить состояние:

```js
  // Накопитель повторов: первую строку пишем сразу (ошибку надо видеть немедленно),
  // а её повторы копим и выдаём одной сводкой.
  let pending = null; // { key, service, level, firstMs, lastMs, count }
```

Заменить функцию `log` и пустой `flush` на:

```js
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
    const key = `${service} ${normalize(text)}`;

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
```

В возвращаемом объекте заменить `flush: () => {}` на `flush: flushPending`.

После создания объекта, но до `return`, добавить таймер, чтобы сводка выходила
даже когда поток строк прекратился:

```js
  // Без таймера сводка про повторы висела бы в памяти до следующей ДРУГОЙ строки —
  // а её может не быть часами, и как раз в этот момент человек скачивает лог.
  if (!opts.now) {
    const ticker = setInterval(() => {
      if (pending && pending.count > 0 && Date.now() - pending.firstMs >= opts.collapseMs) flushPending();
    }, 5000);
    ticker.unref(); // таймер не должен держать процесс живым сам по себе
  }
```

В `module.exports` добавить `isNoise` и `normalize`:

```js
module.exports = { createLogger, formatLine, isNoise, normalize, LEVELS };
```

- [ ] **Step 4: Убедиться, что все тесты проходят**

```
node --test logger.test.js
```

Ожидается: PASS, 16 тестов.

- [ ] **Step 5: Коммит**

```bash
git add server/stream/panel/logger.js server/stream/panel/logger.test.js
git commit -m "фильтр логов: чёрный список и схлопывание повторов"
```

---

### Task 3: Проводка в панель — папка logs, compose, Dockerfile

**Files:**
- Modify: `server/stream/docker-compose.yml`
- Modify: `server/stream/panel/Dockerfile`
- Modify: `server/stream/panel/server.js:1` (блок require) и `server.js:1647` (строка `server.listen`)
- Modify: `server/stream/.gitignore`
- Modify: `server/stream/start.bat`

**Interfaces:**
- Consumes: `createLogger` из Task 1–2.
- Produces: глобальный `logger` в `server.js` — им пользуются Task 4–8. Вызов: `logger.log('panel', 'info', 'текст')`.

- [ ] **Step 1: Добавить bind, переменные и лимиты в docker-compose.yml**

В сервисе `panel` в блок `environment` дописать:

```yaml
      # Уровень лога панели. debug включают только на время отлова конкретного бага —
      # на нём в файл идут и подробности docker-подписок.
      - LOG_LEVEL=${LOG_LEVEL:-info}
      # Смещение местного времени хоста в минутах (считает start.bat). Внутри
      # Linux-контейнера часы всегда UTC, а лог читает человек по своим часам.
      - TZ_OFFSET_MIN=${TZ_OFFSET_MIN:-0}
```

В блок `volumes` сервиса `panel` дописать:

```yaml
      # Логи всего стека. Папка лежит рядом со start.bat (у установленной копии —
      # %localappdata%\BondcastStream\logs), чтобы файл можно было просто взять и
      # переслать, не залезая в docker.
      - ./logs:/logs
```

Каждому из пяти сервисов (`srs`, `srtla-rec`, `panel`, `asr-worker`, `overlay`) добавить
одинаковый блок на том же уровне, что `ports:`:

```yaml
    # Лимита не было вообще: json-file рос на диске неограниченно, а на долгом эфире
    # это гигабайты. Наш собственный лог живёт отдельно, в ./logs — этот блок страхует
    # случай, когда панель не работает и собирать логи некому.
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
```

- [ ] **Step 2: Добавить новые файлы в образ панели**

В `server/stream/panel/Dockerfile` заменить строку `COPY server.js ./` на:

```dockerfile
COPY server.js logger.js ./
```

- [ ] **Step 3: Игнорировать папку логов в git**

В `server/stream/.gitignore` дописать:

```
# Логи стека — машинно-специфичны, пишет панель (panel/logger.js)
logs/
```

- [ ] **Step 4: Считать смещение времени в start.bat**

В `server/stream/start.bat`, рядом с блоком, где считаются `HOST_IPS`/`LAN_IPS`
(строки 42–55), добавить:

```bat
:: Смещение местного времени в минутах — панель живёт в контейнере с UTC-часами,
:: без этого время в логах не сойдётся с тем, когда человек реально стримил.
set "TZ_OFFSET_MIN=0"
for /f "delims=" %%a in ('powershell -NoProfile -Command "[int][System.TimeZoneInfo]::Local.GetUtcOffset((Get-Date)).TotalMinutes"') do set "TZ_OFFSET_MIN=%%a"
```

- [ ] **Step 5: Подключить логгер в server.js**

В `server/stream/panel/server.js` после строки `const { OBSWebSocket } = require('obs-websocket-js');` (строка 12) добавить:

```js
const { createLogger } = require('./logger');

// Единый логгер стека: сюда пишет и сама панель, и коллектор докеровских логов
// (см. attachCollector ниже). Папка /logs bind-монтирована с хоста — файл оттуда
// человек пересылает как есть, когда просят разобраться, почему не работает.
const logger = createLogger({
  dir: process.env.LOGS_DIR || '/logs',
  level: process.env.LOG_LEVEL || 'info',
  tzOffsetMin: Number(process.env.TZ_OFFSET_MIN) || 0,
});
```

Заменить последнюю строку файла (`server.listen(...)`, строка 1647) на:

```js
server.listen(port, () => {
  console.log(`stream-panel listening on :${port}`);
  logger.log('panel', 'info', `панель запущена на :${port}, лог: ${logger.filePath}`);
});
```

- [ ] **Step 6: Проверить, что стек поднимается и файл появился**

```
docker compose up -d --build panel
```

Затем:

```
type logs\bondcast.log
```

Ожидается: одна строка вида `2026-08-30 14:03:11.482  INFO   panel     панель запущена на :8081, лог: /logs/bondcast.log`, время — местное.

- [ ] **Step 7: Коммит**

```bash
git add server/stream/docker-compose.yml server/stream/panel/Dockerfile server/stream/panel/server.js server/stream/.gitignore server/stream/start.bat
git commit -m "панель пишет лог в папку logs на хосте"
```

---

### Task 4: Коллектор докеровских логов сервисов

**Files:**
- Modify: `server/stream/panel/server.js` (вставка после блока `LOGGABLE`, строка ~294)

**Interfaces:**
- Consumes: `logger` (Task 3), `docker` (существует, `server.js:16`).
- Produces: `startCollectors(): void` — вызывается один раз при старте; дальше работает сам.

- [ ] **Step 1: Добавить коллектор в server.js**

Вставить в `server/stream/panel/server.js` сразу после определения `checkLoggable`
(после строки ~294, перед `app.get('/api/connections', ...)`):

```js
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
```

- [ ] **Step 2: Проверить, что строки сервисов попадают в файл**

```
docker compose up -d --build panel
docker restart srs
```

Затем:

```
findstr /C:"  srs " logs\bondcast.log
```

Ожидается: строки SRS о старте (`SRS/6.0`, `listen at ...`), но **ни одной** строки с `Hybrid cpu=`.

- [ ] **Step 3: Проверить переподписку**

```
docker restart srtla-rec
findstr /C:"подписываюсь на его лог" logs\bondcast.log
```

Ожидается: строка `контейнер srtla-rec запустился — подписываюсь на его лог`, а после неё — свежие строки srtla-rec.

- [ ] **Step 4: Проверить, что шума действительно нет**

Оставить стек работать 10 минут без эфира, затем:

```
find /c /v "" logs\bondcast.log
```

Ожидается: единицы строк. До фильтра SRS один давал бы ~120 строк за те же 10 минут.

- [ ] **Step 5: Коммит**

```bash
git add server/stream/panel/server.js
git commit -m "коллектор логов сервисов с переподпиской"
```

---

### Task 5: Логирование событий самой панели

**Files:**
- Modify: `server/stream/panel/server.js` — точки: `/api/containers/:name/start` (~789), `/recreate` (~798), `/stop` (~820), `/api/reachability` (~766), `monitorTick` (~1013), `/api/captions/build` (~1297), `/api/captions/connect` (~1415), `/api/captions/disconnect` (~1446), `/api/update/progress` (~265), обработчик ошибок в конце файла.

**Interfaces:**
- Consumes: `logger` (Task 3).
- Produces: ничего нового наружу.

- [ ] **Step 1: Логировать управление контейнерами**

В `/api/containers/:name/start` заменить тело `try/catch`:

```js
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
```

Так же в `/stop` (`остановка контейнера ...`) и в `/recreate` (`пересоздание контейнера ...`),
включая ветку `PROJECT_ROOT не задан` — там `logger.log('panel', 'error', 'пересоздание невозможно: PROJECT_ROOT не задан')`.

- [ ] **Step 2: Логировать вердикт проверки порта**

В `/api/reachability`, в блоке `try` перед `res.json`:

```js
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
```

- [ ] **Step 3: Логировать появление и пропажу публикатора**

В `monitorTick`, после вычисления `liveNames` и до работы с `sceneSwitcher`, заменить два цикла:

```js
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
```

Сам `monitorTick` вызывается раз в 2 секунды — логируем только переходы, а не каждый тик.
Ветку `catch (e) { return; }` в начале функции оставить без логирования: она срабатывает
на каждое моргание SRS и стала бы новым источником повторов.

- [ ] **Step 4: Логировать жизненный цикл субтитров**

В `/api/captions/build` сразу после старта сборки образа:

```js
    logger.log('panel', 'info', 'началась сборка образа субтитров');
```

В `/api/captions/connect` — имя стрима лежит в переменной `name`, модель в `asrModel`.
После `await container.start();` и в `catch`:

```js
    await container.start();
    watchCaptionsReadiness(container); // не await — следит в фоне, ответ не блокирует
    logger.log('panel', 'info', `субтитры подключены к потоку "${name}", модель ${asrModel}`);
    res.json({ ok: true, streamName: name });
  } catch (e) {
    logger.log('panel', 'error', `субтитры не подключились к "${name}": ${e.message}`);
    res.status(500).json({ error: e.message });
  }
```

В `/api/captions/disconnect` после `captionsReady = false;`:

```js
    logger.log('panel', 'info', 'субтитры отключены');
```

и в его `catch` — `logger.log('panel', 'error', \`субтитры не отключились: ${e.message}\`);`.

В `/api/captions/enroll` в начале обработчика, после проверки входных данных:

```js
  logger.log('panel', 'info', `запись эталона голоса, ${ENROLL_DURATION_SEC} с`);
```

- [ ] **Step 5: Логировать ход обновления**

В `/api/update/progress` после присваивания `updateProgress`:

```js
  // Установщик шлёт прогресс часто; в лог кладём только смену статуса, иначе
  // получим сотню строк «идёт загрузка, 37%».
  if (updateProgress.status !== lastLoggedUpdateStatus) {
    lastLoggedUpdateStatus = updateProgress.status;
    logger.log('panel', 'info', `обновление: ${updateProgress.status} ${updateProgress.message}`.trim());
  }
```

Рядом с `let updateProgress = ...` объявить `let lastLoggedUpdateStatus = null;`.

- [ ] **Step 6: Логировать все 5xx одной строкой**

В самый конец файла, **после** всех `app.get/post/...` и **перед** `const port = process.env.PORT || 8081;`:

```js
// Обработчик ошибок express должен стоять после всех маршрутов, иначе он их не увидит.
// Ловит то, что не поймали сами обработчики — необработанные исключения в async-роутах
// express 4 сюда не попадают, поэтому это именно сеть безопасности, а не единственный
// источник записей об ошибках.
app.use((err, req, res, next) => {
  logger.log('panel', 'error', `${req.method} ${req.path}: ${err.message}`);
  if (res.headersSent) return next(err);
  res.status(500).json({ error: err.message });
});
```

- [ ] **Step 7: Проверить руками**

```
docker compose up -d --build panel
docker restart srs
```

В браузере открыть панель, нажать проверку портов, остановить и запустить `srtla-rec`
кнопками в интерфейсе. Затем:

```
findstr /C:"  panel " logs\bondcast.log
```

Ожидается: строки про запуск/остановку контейнера и про вердикт проверки порта.
Строк про `/api/streams` и `/api/status` быть **не должно** — открытая панель опрашивает
их постоянно, и они бы залили файл.

- [ ] **Step 8: Коммит**

```bash
git add server/stream/panel/server.js
git commit -m "панель логирует свои события"
```

---

### Task 6: Ошибки фронта попадают в тот же лог

**Files:**
- Modify: `server/stream/panel/server.js` — новый маршрут рядом с `/api/status` (~769)
- Modify: `server/stream/panel/public/app.js` — начало файла, рядом с блоком вкладок (~строка 75)

**Interfaces:**
- Consumes: `logger` (Task 3).
- Produces: `POST /api/logs/client`, тело `{ message: string, stack?: string, url?: string }`, ответ `{ ok: true }`.

- [ ] **Step 1: Добавить маршрут в server.js**

Вставить в `server/stream/panel/server.js` перед `app.get('/api/status', ...)`:

```js
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
```

- [ ] **Step 2: Отправлять ошибки из app.js**

Вставить в `server/stream/panel/public/app.js` сразу после блока `// --- Вкладки ---`
(перед `const TAB_KEY`):

```js
// --- Отправка ошибок страницы в лог панели ---------------------------------
// Сломавшийся фронт молчит: пользователь видит белый экран и не может ничего
// показать. Отправляем в тот же лог, что и всё остальное, — под именем "ui".
function reportClientError(message, stack) {
  try {
    fetch('/api/logs/client', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: String(message), stack: String(stack || ''), url: location.href }),
    }).catch(() => {});
  } catch (e) {
    // Панель недоступна — сообщать всё равно некуда, молчим.
  }
}

window.addEventListener('error', (e) => {
  reportClientError(e.message, e.error && e.error.stack);
});
window.addEventListener('unhandledrejection', (e) => {
  const reason = e.reason;
  reportClientError(reason && reason.message ? reason.message : String(reason), reason && reason.stack);
});
```

- [ ] **Step 3: Проверить руками**

```
docker compose up -d --build panel
```

Открыть панель, в консоли браузера выполнить:

```js
setTimeout(() => { throw new Error('проверка лога'); }, 0);
```

Затем:

```
findstr /C:"проверка лога" logs\bondcast.log
```

Ожидается: строка с сервисом `ui`, уровнем `ERROR`, текстом ошибки и адресом страницы.

- [ ] **Step 4: Проверить ограничение частоты**

В консоли браузера:

```js
for (let i = 0; i < 50; i++) fetch('/api/logs/client', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message:'спам '+i})});
```

Затем:

```
findstr /C:"спам" logs\bondcast.log | find /c /v ""
```

Ожидается: не больше 20 строк (часть ещё и схлопнется в сводку — это нормально).

- [ ] **Step 5: Коммит**

```bash
git add server/stream/panel/server.js server/stream/panel/public/app.js
git commit -m "ошибки фронта пишутся в лог панели"
```

---

### Task 7: Выгрузка логов одним файлом + кнопка «Скачать логи»

**Files:**
- Modify: `server/stream/panel/server.js` — новый маршрут рядом с `/api/logs/client`
- Modify: `server/stream/panel/public/index.html` — карточка в сайдбаре (после блока `page-sidebar`, ~строка 718)

**Interfaces:**
- Consumes: `logger.readFiles()` (Task 1), `docker` (существует).
- Produces: `GET /api/logs/bundle` — `text/plain`, `Content-Disposition: attachment`.

- [ ] **Step 1: Добавить маршрут выгрузки**

Вставить в `server/stream/panel/server.js` после маршрута `/api/logs/client`:

```js
// Выгрузка всего, что нужно постороннему человеку, чтобы понять, почему у
// пользователя не работает. Один .txt, а не zip: не нужна новая зависимость,
// а текстовый файл проще переслать в мессенджер и открыть чем угодно.
const BUNDLE_SERVICES = ['srs', 'srtla-rec', 'panel', 'overlay', CAPTIONS_CONTAINER];

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
```

- [ ] **Step 2: Добавить кнопку в интерфейс**

В `server/stream/panel/public/index.html` после закрывающего `</div>` карточки
`page-sidebar` (после блока с `id="serverStreams"`) вставить:

```html
<div class="card page-sidebar">
  <div class="section-title" style="margin-bottom:4px">Что-то не работает?</div>
  <div class="row-meta" style="margin-bottom:12px">Скачай файл с логами и пришли его — по нему видно, что именно сломалось</div>
  <a class="btn-link" href="/api/logs/bundle" download>Скачать логи</a>
</div>
```

Класс `btn-link` уже есть в стилях (`index.html:314`) — это `<a>`, выглядящий как кнопка;
именно им размечена ссылка в веб-морду роутера.

- [ ] **Step 3: Проверить содержимое выгрузки**

```
curl -s -o bundle.txt http://localhost:8081/api/logs/bundle
type bundle.txt | more
```

Ожидается: шапка с версией, версией Docker, статусами контейнеров; затем секция
`=== bondcast.log ===`; затем секции `=== docker logs <сервис> ===`.

- [ ] **Step 4: Проверить, что паролей внутри нет**

Поднять панель с заданным паролем и убедиться, что его значения в файле нет:

```
findstr /C:"(задан)" bundle.txt
findstr /C:"PANEL_PASS" bundle.txt
```

Ожидается: строка `PANEL_USER: ... PANEL_PASS: (задан) ...`, самого пароля в файле нет.

- [ ] **Step 5: Проверить кнопку в браузере**

Открыть панель, нажать «Скачать логи» — браузер должен сохранить файл
`bondcast-logs-<дата>.txt`, который открывается в блокноте без каши в кодировке.

- [ ] **Step 6: Коммит**

```bash
git add server/stream/panel/server.js server/stream/panel/public/index.html
git commit -m "выгрузка логов одним файлом и кнопка в панели"
```

---

### Task 8: Скрытая вкладка «Диагностика» с живым логом

**Files:**
- Modify: `server/stream/panel/server.js` — новый маршрут рядом с `/api/logs/bundle`
- Modify: `server/stream/panel/public/index.html` — `.tabs-nav` (~571), новая `.tab-panel`, стили
- Modify: `server/stream/panel/public/app.js` — блок вкладок (~80–103), новый блок диагностики, текст на строке ~503
- Modify: `server/stream/README.md`

**Interfaces:**
- Consumes: `logger.tail()`, `logger.subscribe()`, `logger.unsubscribe()` (Task 1).
- Produces: `GET /api/logs/stream` — SSE, каждое событие `data: <строка лога>`.

- [ ] **Step 1: Добавить SSE-маршрут**

Вставить в `server/stream/panel/server.js` после маршрута `/api/logs/bundle`:

```js
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

  // Тот же приём, что у /api/containers/:name/logs выше — прокси и браузеры
  // рвут молчащее SSE-соединение.
  const keepAlive = setInterval(() => res.write(':keep-alive\n\n'), 15000);

  req.on('close', () => {
    clearInterval(keepAlive);
    logger.unsubscribe(onLine);
  });
});
```

- [ ] **Step 2: Добавить разметку вкладки**

В `server/stream/panel/public/index.html` в `.tabs-nav` (строка ~571) дописать третью кнопку:

```html
    <button class="tab-btn" data-tab="diagnostics" id="diagnosticsTabBtn" hidden>Диагностика</button>
```

Перед закрывающим `</div>` блока `page-main` (после панели `data-tab-panel="stream"`) вставить:

```html
  <!-- ============================ Диагностика ============================ -->
  <!-- Скрытая вкладка: обычному пользователю она не нужна и только пугает,
       ему хватает кнопки «Скачать логи». Открывается адресом с #debug. -->
  <div class="tab-panel" data-tab-panel="diagnostics">
    <div class="card">
      <div class="section-title" style="margin-bottom:12px">Живой лог</div>
      <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:10px">
        <select id="diagService">
          <option value="">все сервисы</option>
          <option value="panel">panel</option>
          <option value="srs">srs</option>
          <option value="srtla-rec">srtla-rec</option>
          <option value="overlay">overlay</option>
          <option value="asr-worker">asr-worker</option>
          <option value="ui">ui (браузер)</option>
        </select>
        <select id="diagLevel">
          <option value="">любой уровень</option>
          <option value="INFO">info и выше</option>
          <option value="WARN">warn и выше</option>
          <option value="ERROR">только error</option>
        </select>
        <input id="diagSearch" type="text" placeholder="поиск по тексту" style="flex:1;min-width:140px">
        <button id="diagPause">Пауза</button>
        <button id="diagClear">Очистить</button>
        <a class="btn-link" href="/api/logs/bundle" download>Скачать логи</a>
      </div>
      <pre id="diagLog" class="log-box" style="height:420px"></pre>
    </div>
  </div>
```

- [ ] **Step 3: Добавить логику вкладки в app.js**

В `server/stream/panel/public/app.js` заменить строку `const TABS = ['quickstart', 'stream'];` на:

```js
// Диагностика — скрытая вкладка: адрес с #debug включает её насовсем (запоминаем
// в localStorage), чтобы не приходилось дописывать якорь при каждой перезагрузке.
const DEBUG_KEY = 'bondcast_debug';
if (location.hash === '#debug') localStorage.setItem(DEBUG_KEY, '1');
const debugEnabled = localStorage.getItem(DEBUG_KEY) === '1';
const TABS = debugEnabled ? ['quickstart', 'stream', 'diagnostics'] : ['quickstart', 'stream'];
```

В функцию `applyUiState` перед существующими `forEach` добавить показ кнопки:

```js
  const diagBtn = document.getElementById('diagnosticsTabBtn');
  if (diagBtn) diagBtn.hidden = !debugEnabled;
```

В конец `app.js` добавить блок диагностики:

```js
// --- Вкладка «Диагностика» -------------------------------------------------
// Тот же поток строк, что уходит в файл (см. panel/logger.js), только живьём.
// Фильтры чисто клиентские: строк в буфере максимум 500 + новые, фильтровать на
// сервере смысла нет, а переподключать SSE на каждое изменение фильтра — вредно.
if (debugEnabled) {
  const diagLog = document.getElementById('diagLog');
  const diagService = document.getElementById('diagService');
  const diagLevel = document.getElementById('diagLevel');
  const diagSearch = document.getElementById('diagSearch');
  const diagPause = document.getElementById('diagPause');
  const diagClear = document.getElementById('diagClear');

  const LEVEL_ORDER = ['DEBUG', 'INFO', 'WARN', 'ERROR'];
  let diagLines = [];
  let diagPaused = false;

  // Разбираем строку регуляркой, а не срезами по фиксированным позициям: имена
  // сервисов разной длины ("asr-worker" длиннее колонки), и срезы бы поехали.
  const DIAG_RE = /^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(DEBUG|INFO|WARN|ERROR)\s+(\S+)\s(.*)$/;

  function diagMatches(line) {
    const needleOnly = diagSearch.value.trim().toLowerCase();
    const m = DIAG_RE.exec(line);
    // Служебные строки («связь оборвалась») не имеют сервиса и уровня — их
    // фильтруем только поиском, иначе они бы пропадали при любом выборе сервиса.
    if (!m) return !needleOnly || line.toLowerCase().includes(needleOnly);
    const [, , level, service] = m;
    if (diagService.value && service !== diagService.value) return false;
    const min = diagLevel.value;
    if (min && LEVEL_ORDER.indexOf(level) < LEVEL_ORDER.indexOf(min)) return false;
    const needle = diagSearch.value.trim().toLowerCase();
    if (needle && !line.toLowerCase().includes(needle)) return false;
    return true;
  }

  function renderDiag() {
    diagLog.textContent = diagLines.filter(diagMatches).join('\n');
    if (!diagPaused) diagLog.scrollTop = diagLog.scrollHeight;
  }

  diagService.onchange = renderDiag;
  diagLevel.onchange = renderDiag;
  diagSearch.oninput = renderDiag;
  diagClear.onclick = () => { diagLines = []; renderDiag(); };
  diagPause.onclick = () => {
    diagPaused = !diagPaused;
    diagPause.textContent = diagPaused ? 'Продолжить' : 'Пауза';
  };

  const diagSource = new EventSource('/api/logs/stream');
  diagSource.onmessage = (e) => {
    diagLines.push(e.data);
    // Тот же потолок, что у кольцевого буфера на сервере — страница не должна
    // расти в памяти бесконечно за сутки открытой вкладки.
    if (diagLines.length > 2000) diagLines.shift();
    if (!diagPaused) renderDiag();
  };
  diagSource.onerror = () => {
    diagLines.push('[связь с логом оборвалась, перезагрузи страницу]');
    renderDiag();
  };
}
```

- [ ] **Step 4: Починить текст про несуществующую вкладку**

В `server/stream/panel/public/app.js` (строка ~503) заменить:

```js
          <p>Смотри лог сервиса на вкладке «Функции» → «Диагностика»: там будет строка,
          на которой он падает. Чаще всего это занятый порт или испорченный
          <code>srs.conf</code>. Ярлык «Запустить трансляцию» пересобирает контейнеры
          с нуля и лечит второй случай.</p>`),
```

на:

```js
          <p>Нажми «Скачать логи» в блоке «Что-то не работает?» справа — там будет строка,
          на которой сервис падает. Чаще всего это занятый порт или испорченный
          <code>srs.conf</code>. Ярлык «Запустить трансляцию» пересобирает контейнеры
          с нуля и лечит второй случай.</p>`),
```

- [ ] **Step 5: Проверить руками**

```
docker compose up -d --build panel
```

Открыть `http://localhost:8081/#debug` — должна появиться третья вкладка. На ней:
идут строки лога; выбор сервиса `srs` оставляет только строки SRS; уровень `ERROR`
оставляет только ошибки; поиск фильтрует по подстроке; «Пауза» останавливает
автопрокрутку. Затем открыть панель по адресу без `#debug` — вкладка должна
остаться (запомнилась). Проверить в приватном окне, что там вкладки нет.

- [ ] **Step 6: Описать логи в README**

В `server/stream/README.md` добавить раздел:

```markdown
## Логи

Панель собирает логи всех сервисов (`srs`, `srtla-rec`, `overlay`, `asr-worker`) и свои
собственные события в один файл — папка `logs` рядом со `start.bat`
(у установленной копии — `%localappdata%\BondcastStream\logs`). Файл `bondcast.log`,
ротация по 5 МБ, всего три файла.

**Если что-то не работает** — нажми «Скачать логи» в панели и пришли получившийся
`bondcast-logs-<дата>.txt`. В нём версия установки, версия Docker, статус контейнеров
и сами логи. Паролей панели и токенов в файле нет.

**Живой лог** — вкладка «Диагностика», открывается адресом `http://localhost:8081/#debug`
и после этого остаётся видимой. Там фильтр по сервису, по уровню и поиск.

**Шум режется** в `panel/logger.js`: список `NOISE` выкидывает заведомо бесполезные
строки (телеметрия SRS каждые 5 секунд, keepalive srtla, отдача статики оверлеем),
а одинаковые строки подряд схлопываются в одну со счётчиком `↑ повторилось ещё ×47`.
Если в логе завёлся новый повтор — добавляй регулярку в `NOISE` с комментарием, почему
эта строка бесполезна, и тест в `panel/logger.test.js`.

Тесты фильтра: `cd panel && node --test logger.test.js`.
```

- [ ] **Step 7: Прогнать тесты целиком**

```
cd panel && node --test logger.test.js
```

Ожидается: PASS, 16 тестов.

- [ ] **Step 8: Коммит**

```bash
git add server/stream/panel/server.js server/stream/panel/public/index.html server/stream/panel/public/app.js server/stream/README.md
git commit -m "вкладка диагностики с живым логом"
```

---

## Финальная проверка (после всех задач)

- [ ] `cd server/stream/panel && node --test logger.test.js` — 16 тестов PASS.
- [ ] `cd server/stream && docker compose up -d --build` — стек поднимается.
- [ ] Час эфира с телефона: в `logs\bondcast.log` сотни строк, не десятки тысяч
      (`find /c /v "" logs\bondcast.log`).
- [ ] `docker restart srs` — в логе появляется строка про переподписку, дальше строки SRS идут снова.
- [ ] Кнопка «Скачать логи» отдаёт читаемый файл без паролей.
- [ ] `#debug` открывает вкладку с живым логом, фильтры работают.
- [ ] `docker inspect srs --format "{{.HostConfig.LogConfig}}"` показывает `max-size:10m`.
