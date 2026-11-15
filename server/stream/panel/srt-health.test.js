const test = require('node:test');
const assert = require('node:assert');

const { createSrtHealth, parseStatsLine, suggestKbps } = require('./srt-health');

// Строка в том виде, в каком её отдаёт docker logs у SRS 6.
const line = (cid, recv, loss, retrans, drop) =>
  `[2026-11-13 16:33:40.123][INFO][1][${cid}] <- SRT_CPB Transport Stats # pktRecv=${recv}, pktRcvLoss=${loss}, pktRcvRetrans=${retrans}, pktRcvDrop=${drop}`;

// Часы ручные: каждая строка статистики приходит на 10 с позже предыдущей.
function setup() {
  let clock = 1_000_000;
  const health = createSrtHealth({ now: () => clock });
  const feed = (cid, rows) => {
    let last = null;
    for (const [recv, loss, retrans, drop] of rows) {
      clock += 10_000;
      last = health.feed(line(cid, recv, loss, retrans, drop));
    }
    return last;
  };
  return { health, feed, tick: (ms) => { clock += ms; }, now: () => clock };
}

test('разбирает только статистику издателя', () => {
  assert.deepStrictEqual(parseStatsLine(line('8871a9cg', 4838, 3339, 3594, 0)), { cid: '8871a9cg', recv: 4838, loss: 3339, retrans: 3594, drop: 0 });
  // Так строка выглядит в docker logs на самом деле: SRS начинает её сбросом цвета.
  assert.deepStrictEqual(parseStatsLine(`\x1b[0m${line('j73h3kw1', 4701, 2384, 1210, 1700)}`), { cid: 'j73h3kw1', recv: 4701, loss: 2384, retrans: 1210, drop: 1700 });
  // Тот же текст в середине чужой строки (клиент прислал его как streamid) — не статистика.
  assert.strictEqual(parseStatsLine(`[2026-11-13 16:33:40.123][WARN][1][x1y2z3w4][22] srt streamid=${line('8871a9cg', 1, 9999, 9999, 9999)}`), null);
  assert.strictEqual(parseStatsLine(`${line('8871a9cg', 4838, 3339, 3594, 0)} и хвост`), null);
  assert.strictEqual(parseStatsLine('[2026-11-13 16:33:40.123][INFO][1][v4oy48fg] -> SRT_CPB Transport Stats # pktSent=4838, pktSndLoss=0, pktRetrans=0, pktSndDrop=0'), null);
  assert.strictEqual(parseStatsLine('[2026-11-13 16:33:40.123][INFO][1][8871a9cg] <- SRT_CPB time=10001, packets=1741, okbps=0,0,0, ikbps=0,0,0'), null);
});

test('чистый эфир на 5,9 Мбит/с — без вердикта (сессия 9 ноября)', () => {
  const { health, feed } = setup();
  const r = feed('44776o91', [[2998, 0, 0, 0], [5241, 0, 0, 0], [4125, 0, 0, 0], [5069, 0, 0, 0], [5009, 0, 0, 0], [5107, 0, 0, 0], [5594, 0, 0, 0]]);
  assert.strictEqual(r.verdict, null);
  assert.strictEqual(health.verdictFor('44776o91'), null);
});

test('ровный приём с повторами — потолок и совет по битрейту (сессия 13 ноября)', () => {
  const { health, feed } = setup();
  const r = feed('8871a9cg', [[2901, 2, 2, 0], [5110, 0, 0, 0], [4891, 2243, 2402, 0], [4838, 3339, 3594, 0], [4838, 3484, 3414, 0]]);
  assert.strictEqual(r.changed, true);
  assert.strictEqual(r.verdict.state, 'limited');
  assert.ok(Math.abs(r.verdict.ceilingMbps - 5.09) < 0.02, `потолок ${r.verdict.ceilingMbps}`);
  assert.strictEqual(r.verdict.suggestKbps, 3500);
  assert.strictEqual(health.verdictFor('8871a9cg').state, 'limited');
});

test('кодер с ограниченными повторами под потолком: повторов мало, но доходит меньше, чем он шлёт', () => {
  // Стенд: ffmpeg/libsrt с oheadbw=25 шлёт 5870 пакетов за 10 с через ограничитель 5 Мбит/с.
  const { feed } = setup();
  const r = feed('j73h3kw1', [[4699, 2385, 1197, 1780], [4708, 2399, 1218, 1740], [4677, 2395, 1212, 1709]]);
  assert.strictEqual(r.verdict.state, 'limited');
  assert.strictEqual(r.verdict.reason, 'shortfall');
  assert.ok(r.verdict.retransShare < 0.3);
  assert.strictEqual(r.verdict.suggestKbps, 3000);
});

test('постоянный битрейт и случайные потери, которые повторы закрывают, — не потолок', () => {
  // Теряется каждый десятый пакет, почти всё восстановлено: приём не ниже собственного потока.
  const { feed } = setup();
  const r = feed('cbrloss1', [[5811, 587, 528, 4], [5820, 590, 540, 6], [5805, 580, 520, 3]]);
  assert.strictEqual(r.verdict.state, 'dropping');
});

test('ровный приём без повторов — не потолок: кодер просто держит постоянный битрейт', () => {
  const { feed } = setup();
  const r = feed('cbr00001', [[4840, 0, 0, 0], [4845, 0, 0, 0], [4838, 0, 0, 0], [4841, 0, 0, 0]]);
  assert.strictEqual(r.verdict, null);
});

test('рваные потери без потолка, пакеты опаздывают — «теряются по пути»', () => {
  const { feed } = setup();
  const r = feed('lossy001', [[3000, 200, 180, 0], [3600, 400, 350, 40], [2900, 500, 420, 25]]);
  assert.strictEqual(r.verdict.state, 'dropping');
  assert.strictEqual(r.verdict.dropped, 65);
});

test('восстановленные потери без опозданий — молчим (так ведёт себя проброс порта Docker Desktop)', () => {
  const { feed } = setup();
  const r = feed('reorder1', [[5200, 260, 260, 0], [5900, 300, 300, 0], [5400, 280, 280, 0]]);
  assert.strictEqual(r.verdict, null);
});

test('потолок держится, пока потери не кончились, и снимается после трёх чистых интервалов', () => {
  const { feed } = setup();
  feed('0893626i', [[4848, 3100, 3200, 60], [4876, 3300, 3400, 80], [4853, 3200, 3300, 70]]);
  // Провал приёма посреди потерь вердикт не снимает — иначе плашка в панели мигала бы.
  assert.strictEqual(feed('0893626i', [[4314, 2000, 2100, 90]]).verdict.state, 'limited');
  assert.strictEqual(feed('0893626i', [[3500, 0, 0, 0], [3480, 0, 0, 0]]).verdict.state, 'limited');
  const r = feed('0893626i', [[3510, 0, 0, 0]]);
  assert.strictEqual(r.changed, true);
  assert.strictEqual(r.verdict, null);
});

test('потолок снимается, когда приём уверенно пошёл выше него, даже если мелкие потери остались', () => {
  // Стенд: ограничитель убрали, осталось 15 % потерь, которые повторы закрывают.
  const { feed } = setup();
  feed('j73h3kw1', [[4699, 2385, 1197, 1780], [4708, 2399, 1218, 1740], [4677, 2395, 1212, 1709]]);
  // Один интервал выше потолка — это накопленный запас ограничителя, а не его исчезновение.
  assert.strictEqual(feed('j73h3kw1', [[6477, 875, 1449, 90]]).verdict.state, 'limited');
  assert.strictEqual(feed('j73h3kw1', [[6042, 875, 1032, 0]]).verdict.state, 'limited');
  const r = feed('j73h3kw1', [[5987, 881, 1004, 0]]);
  assert.strictEqual(r.changed, true);
  assert.strictEqual(r.verdict, null);
});

test('потолок не дёргается от интервала к интервалу, но следует за настоящим сдвигом', () => {
  const { feed } = setup();
  // 4,99 и 5,00 Мбит/с лежат по разные стороны шага совета (3000 / 3500 кбит/с).
  const first = feed('f64883x8', [[4757, 4579, 3439, 667], [4736, 4637, 3508, 1131], [4738, 4696, 3600, 1120]]).verdict;
  assert.strictEqual(first.suggestKbps, 3000);
  const same = feed('f64883x8', [[4752, 4600, 3500, 900], [4760, 4610, 3520, 950], [4758, 4640, 3550, 990]]).verdict;
  assert.strictEqual(same.ceilingPps, first.ceilingPps);
  assert.strictEqual(same.suggestKbps, 3000);
  const moved = feed('f64883x8', [[3800, 3000, 2600, 500], [3790, 3050, 2650, 520], [3805, 3020, 2620, 510]]).verdict;
  assert.strictEqual(moved.ceilingPps, 380);
  assert.strictEqual(moved.suggestKbps, 2500);
});

test('издатели не смешиваются, закончившаяся сессия вердикт не отдаёт', () => {
  const { health, feed, tick } = setup();
  feed('aaaa0001', [[4838, 3339, 3594, 0], [4838, 3484, 3414, 0], [4840, 3400, 3500, 0]]);
  feed('bbbb0002', [[5100, 0, 0, 0]]);
  assert.strictEqual(health.verdictFor('aaaa0001').state, 'limited');
  assert.strictEqual(health.verdictFor('bbbb0002'), null);
  assert.strictEqual(health.verdictFor('нет-такого'), null);
  tick(60_000);
  assert.strictEqual(health.verdictFor('aaaa0001'), null);
});

test('сумма за окно берёт только интервалы внутри него', () => {
  const { health, feed, now } = setup();
  const from = now();
  feed('aaaa0001', [[1000, 10, 5, 1], [2000, 20, 10, 2]]);
  const mid = now();
  feed('aaaa0001', [[3000, 30, 15, 3]]);
  assert.deepStrictEqual(health.totalsBetween(from, mid), { intervals: 2, recv: 3000, loss: 30, retrans: 15, drop: 3, recvPps: 150, lossPps: 1.5 });
  assert.deepStrictEqual(health.totalsBetween(mid, now()), { intervals: 1, recv: 3000, loss: 30, retrans: 15, drop: 3, recvPps: 300, lossPps: 3 });
  assert.deepStrictEqual(health.totalsBetween(now(), now() + 1), { intervals: 0, recv: 0, loss: 0, retrans: 0, drop: 0, recvPps: 0, lossPps: 0 });
});

test('скорость потерь двух эфиров складывается, а не усредняется', () => {
  let clock = 0;
  const health = createSrtHealth({ now: () => clock });
  // Два издателя шлют статистику в одни и те же 30 секунд.
  for (const t of [10_000, 20_000, 30_000]) {
    clock = t;
    health.feed(line('aaaa0001', 5000, 3000, 3000, 0));
    health.feed(line('bbbb0002', 2000, 1000, 1000, 0));
  }
  const totals = health.totalsBetween(0, 30_000);
  assert.strictEqual(totals.intervals, 6);
  assert.strictEqual(totals.loss, 12000);
  assert.strictEqual(totals.lossPps, 400);
  assert.strictEqual(totals.recvPps, 700);
});

test('совет по битрейту округляется вниз до шага настроек', () => {
  assert.strictEqual(suggestKbps(5.1), 3500);
  assert.strictEqual(suggestKbps(8.6), 6000);
  assert.strictEqual(suggestKbps(2.0), 1400);
  assert.strictEqual(suggestKbps(0.1), 100);
});
