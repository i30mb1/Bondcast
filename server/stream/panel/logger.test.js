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

// Строки ниже — как их реально пишет SRS 6, вместе с цветовыми кодами: жёлтый включается
// перед WARN, а сброс цвета приезжает в начале следующей строки.
const srsLine = (cid, rest, level = 'INFO') => `[2026-11-13 10:37:33.758][${level}][1][${cid}] ${rest}`;
const pollLines = (cid) => [
  srsLine(cid, 'HTTP #0 172.18.0.3:59620 GET http://srs:1985/api/v1/streams/, content-length=-1'),
  srsLine(cid, 'TCP: before dispose resource(HttpConn)(0x5e29f98e1490), conns=1, zombies=0, ign=0, inz=0, ind=0'),
  `\x1b[33m[2026-11-13 10:37:33.759][WARN][1][${cid}][104] client disconnect peer. ret=1007`,
  `\x1b[0m${srsLine('o2976ce8', 'TCP: clear zombies=1 resources, conns=1, removing=0, unsubs=0')}`,
  srsLine(cid, 'TCP: disposing #0 resource(HttpConn)(0x5e29f98e1490), conns=1, disposing=1, zombies=0'),
];

test('опрос API панелью не попадает в лог — все пять строк соединения', () => {
  const dir = tmpDir();
  const logger = createLogger({ dir });
  for (const line of pollLines('n7q4539f')) logger.log('srs', 'info', line);
  for (const line of pollLines('72974698')) logger.log('srs', 'info', line);
  assert.strictEqual(logger.tail().length, 0);
  // Соединение закрыто — его cid снова обычный, а не «глушить всё, что под ним придёт».
  logger.log('srs', 'info', srsLine('n7q4539f', 'SRT client ip=172.18.0.6:41559, fd=681887608'));
  assert.strictEqual(logger.tail().length, 1);
});

test('те же служебные строки про настоящего зрителя остаются', () => {
  const dir = tmpDir();
  const logger = createLogger({ dir });
  // Зритель по HTTP-FLV между двумя опросами: его запрос и его отключение нужны в логе.
  for (const line of pollLines('n7q4539f')) logger.log('srs', 'info', line);
  logger.log('srs', 'info', srsLine('abcd1234', 'HTTP #0 172.18.0.1:43982 GET http://192.168.31.133:8080/live/foo.flv, content-length=-1'));
  for (const line of pollLines('72974698')) logger.log('srs', 'info', line);
  logger.log('srs', 'info', srsLine('abcd1234', 'TCP: before dispose resource(HttpConn)(0x5e29f98e4270), conns=1, zombies=0, ign=0, inz=0, ind=0'));
  logger.log('srs', 'warn', `\x1b[33m[2026-11-13 10:37:40.000][WARN][1][abcd1234][104] client disconnect peer. ret=1007`);
  logger.log('srs', 'info', `\x1b[0m${srsLine('abcd1234', 'TCP: disposing #0 resource(HttpConn)(0x5e29f98e4270), conns=1, disposing=1, zombies=0')}`);
  const lines = logger.tail();
  assert.strictEqual(lines.length, 4);
  assert.ok(lines[0].includes('/live/foo.flv'));
  assert.ok(lines[2].includes('client disconnect peer'));
});

test('опрос глушится только в логе SRS — чужая строка такого же вида проходит', () => {
  const dir = tmpDir();
  const logger = createLogger({ dir });
  logger.log('asr-worker', 'info', srsLine('n7q4539f', 'HTTP #0 172.18.0.3:59620 GET http://srs:1985/api/v1/streams/, content-length=-1'));
  assert.strictEqual(logger.tail().length, 1);
});

test('цветовые коды терминала в лог не попадают', () => {
  const dir = tmpDir();
  const logger = createLogger({ dir });
  logger.log('srs', 'warn', '\x1b[33m[2026-11-13 10:37:33.759][WARN][1][abcd1234][104] srt serve error\x1b[0m');
  const [line] = logger.tail();
  assert.ok(!line.includes('\x1b'));
  assert.ok(line.endsWith('srt serve error'));
});

test('isNoise узнаёт уборку закрытых соединений SRS', () => {
  assert.ok(isNoise(srsLine('o2976ce8', 'TCP: clear zombies=1 resources, conns=1, removing=0, unsubs=0')));
  // У SRT-соединений уборка своя и редкая (раз на подключение) — она остаётся.
  assert.ok(!isNoise(srsLine('3zn85729', 'SRT: clear zombies=1 resources, conns=1, removing=0, unsubs=0')));
});

test('isNoise узнаёт keepalive srtla и статику оверлея', () => {
  assert.ok(isNoise('got keepalive from 1.2.3.4'));
  assert.ok(isNoise('172.18.0.1 - - [30/Aug/2026:14:00:00 +0000] "GET /app.js HTTP/1.1" 200 4096 "-" "Mozilla"'));
  assert.ok(!isNoise('172.18.0.1 - - [30/Aug/2026:14:00:00 +0000] "GET /app.js HTTP/1.1" 500 0 "-" "Mozilla"'));
});
